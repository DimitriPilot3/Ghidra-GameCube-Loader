package gamecubeloader.common;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.Msg;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

public class SymbolLoader {
	private static final long UINT_MASK = 0xFFFFFFFF;
	
	private Program program;
	private String[] lines;
	private List<SymbolInfo> symbols;
	private long objectAddress = 0;
	private int alignment;
	private long bssAddress;
	
	public SymbolLoader(Program program, FileReader reader, long objectAddress, int alignment, long bssAddress) {
		this.program = program;
		lines = new BufferedReader(reader).lines().toArray(String[]::new);
		this.objectAddress = objectAddress;
		this.alignment = alignment;
		this.bssAddress = bssAddress;
	}
	
	private List<MemoryMapSectionInfo> GetMemoryMapInfo() {
		List<MemoryMapSectionInfo> memMapInfo = new ArrayList<MemoryMapSectionInfo>();
		int memMapLineStartIdx = -1;
		
		// Start by grabbing the memory map index.
		// TODO: Some early symbol files do not contain a memory map section. Handle these.
		for (int i = lines.length - 1; i > -1; i--) {
			if (lines[i].contains("Memory map:")) {
				memMapLineStartIdx = i + 2; // Add three to skip the header lines.
				break;
			}
		}
		
		if (memMapLineStartIdx > -1) {
			// Parse the memory map info.
			for (int i = memMapLineStartIdx; i < lines.length; i++) {
				String line = lines[i];
				if (line.equals("") || line.trim().length() == 0) {
					break;
				}
				
				// Split the string by whitespace entries.
				String[] splitInformation = line.trim().split("\\s+");
				
				// Try to parse the information.
				
				// Valid lines have four entries. Name -> Starting Address -> Size -> File Offset
				if (splitInformation.length == 0) break;
				else if (splitInformation.length < 4) continue;
				
				try {
					long startingAddress = Long.parseUnsignedLong(splitInformation[1], 16) & UINT_MASK;
					long size = Long.parseUnsignedLong(splitInformation[2], 16) & UINT_MASK;
					long fileOffset = Long.parseUnsignedLong(splitInformation[3], 16) & UINT_MASK;
					
					memMapInfo.add(new MemoryMapSectionInfo(splitInformation[0], startingAddress, size, fileOffset));
				}
				catch (NumberFormatException e) {
					Msg.error(this, "Symbol Loader: Failed to parse memory map entry: " + splitInformation[0]);
				}
			}
			
			// The first section should start at the base address.
			if (memMapInfo.size() > 0 && memMapInfo.get(0).fileOffset != 0) {
				long adjustOffset = memMapInfo.get(0).fileOffset;
				for (MemoryMapSectionInfo info : memMapInfo) {
					if (info.fileOffset != 0)
					{
						info.fileOffset -= adjustOffset;
					}
				}
			}
			
			
			return memMapInfo;
		}
		
		// TODO: This shouldn't be thrown for symbol maps that are previous formats.
		Msg.error(this, "Symbol Loader: The memory map information couldn't be located. This symbol map cannot be loaded.");
		return null;
	}
	
	private void ParseSymbols() {
		symbols = new ArrayList<SymbolInfo>();
		List<MemoryMapSectionInfo> memMapInfo = GetMemoryMapInfo();
		if (memMapInfo != null) {
			long currentSectionSize = 0;
			long effectiveAddress = this.objectAddress;
			long preBssAddress = -1;
			
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if (line.equals("") || line.trim().length() == 0) continue;
				
				if (line.contains(" section layout")) {
					String sectionName = line.substring(0, line.indexOf(" section layout")).trim();
					Msg.info(this, "Symbol Loader: Switched to symbols for section: " + sectionName);
					
					// Search the info list for the section.
					MemoryMapSectionInfo currentSectionInfo = null;
					for (MemoryMapSectionInfo sectionInfo : memMapInfo) {
						if (sectionInfo.name.equals(sectionName)) {
							currentSectionInfo = sectionInfo;
							break;
						}
					}
					
					if (currentSectionInfo != null) {
						effectiveAddress += currentSectionSize;
						currentSectionSize = currentSectionInfo.size;
						
						// Align the effective address to the section alignment.
						effectiveAddress += (alignment - (effectiveAddress % alignment));
						
						// Check if we should switch to using the bss section address.
						if (currentSectionInfo.name.equals(".bss") && bssAddress != -1) {
							preBssAddress = effectiveAddress;
							effectiveAddress = bssAddress;
						}
						else if (preBssAddress > -1) {
							effectiveAddress = preBssAddress;
							preBssAddress = 0;
						}
					}
					else {
						Msg.info(this, "Symbol Loader: No memory layout information was found for section: " + sectionName);
					}
					
					i += 3; // Skip past the section column data.
				}
				else {
					// We don't want to process these.
					if (line.contains("entry of ")) continue;
					
					String[] splitInformation = line.trim().split("\\s+");
					if (splitInformation.length < 6) continue;
					
					long startingAddress = 0;
					long size = 0;
					long virtualAddress = 0;
					int objectAlignment = 0;
					
					try {
						startingAddress = Long.parseUnsignedLong(splitInformation[0], 16) & UINT_MASK;
						size = Long.parseUnsignedLong(splitInformation[1], 16) & UINT_MASK;
						virtualAddress = Long.parseUnsignedLong(splitInformation[2], 16) & UINT_MASK;
					}
					catch (Exception e) {
						Msg.error(this, "Symbol Loader: Unable to parse symbol information for symbol: " + line);
						continue;
					}
					
					try {
						objectAlignment = Integer.parseInt(splitInformation[3]);
						if (objectAlignment == 1) continue;
					}
					catch (NumberFormatException e) {
						// Do nothing for the object alignment.
					}
						
					SymbolInfo symbolInfo = null;
					
					if (virtualAddress >= objectAddress && virtualAddress < program.getAddressFactory().getDefaultAddressSpace().getMaxAddress().getUnsignedOffset())
					{
						// DOL map files sometimes have their virtual address pre-calculated.
						symbolInfo = new SymbolInfo(splitInformation[4], splitInformation[5], startingAddress,
								size, virtualAddress, objectAlignment);
					}
					else
					{
						symbolInfo = new SymbolInfo(splitInformation[4], splitInformation[5], startingAddress,
							size, virtualAddress + effectiveAddress, objectAlignment);
					}
					
					symbols.add(symbolInfo);
				}
			}
		}
	}
	
	public void ApplySymbols() {
		this.ParseSymbols();
		
		AddressFactory factory = program.getAddressFactory();
		AddressSpace addressSpace = factory.getDefaultAddressSpace();
		SymbolTable symbolTable = program.getSymbolTable();
		Namespace globalNamespace = program.getGlobalNamespace();
		
		for (SymbolInfo symbolInfo : symbols)
		{
			if (symbolInfo.alignment == 1) continue; // Don't bother loading these for now.
			
			// Try getting a namespace first.
			Namespace objectNamespace = null;
			
			try {
				String namespaceName = symbolInfo.container;
				int fileTypeIdx = namespaceName.lastIndexOf('.');
				if (fileTypeIdx > -1)
				{
					namespaceName = namespaceName.substring(0, fileTypeIdx);
				}
				
				objectNamespace = symbolTable.getNamespace(namespaceName, globalNamespace);
				
				if (objectNamespace == null) {
					objectNamespace = symbolTable.createNameSpace(globalNamespace, namespaceName, SourceType.IMPORTED);
				}
			}
			catch (DuplicateNameException | InvalidInputException e) {
				// Do nothing. This should never throw for DuplicateNameException.
				Msg.error(this, "Symbol Loader: An error occurred while creating a namespace for: " + symbolInfo.container);
				e.printStackTrace();
			}
			
			try {
				symbolTable.createLabel(addressSpace.getAddress(symbolInfo.virtualAddress), symbolInfo.name,
					objectNamespace == null ? globalNamespace : objectNamespace, SourceType.ANALYSIS);
			}
			catch (InvalidInputException e) {
				Msg.error(this, "Symbol Loader: An error occurred when attempting to load symbol: " + symbolInfo.name);
			}
		}
	}
}
