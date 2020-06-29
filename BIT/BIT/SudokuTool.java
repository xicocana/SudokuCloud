//
// An adaptation of StatisticsTool.java
//
// This program measures and instruments to obtain different statistics
// about Java programs.
//
// Copyright (c) 1998 by Han B. Lee (hanlee@cs.colorado.edu).
// ALL RIGHTS RESERVED.
//
package BIT;

import BIT.highBIT.*;
import java.io.File;
import java.util.Enumeration;
import java.util.Vector;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import java.lang.*;

public class SudokuTool 
{
	private static Float dyn_method_count = 0F;
	private static Float dyn_bb_count = 0F;
	private static Float dyn_instr_count = 0F;


	public static String classname = "";
		
	public static void printUsage() 
		{
			System.out.println("Syntax: java SudokuTool in_path [out_path]");
			System.out.println();
			System.out.println("        in_path:  directory from which the class files are read");
			System.out.println("        out_path: directory to which the class files are written");
			System.exit(-1);
		}

	public static void doDynamic(File in_dir, File out_dir) 
		{
			String filelist[] = in_dir.list();
			
			for (int i = 0; i < filelist.length; i++) {
				String filename = filelist[i];
				Thread currentThread = Thread.currentThread();
				if (filename.endsWith(".class")) {
					String in_filename = in_dir.getAbsolutePath() + System.getProperty("file.separator") + filename;
					String out_filename = out_dir.getAbsolutePath() + System.getProperty("file.separator") + filename;
					classname = currentThread.getId() + "_" + filename.substring(0, filename.length()-6);
					ClassInfo ci = new ClassInfo(in_filename);
					for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
						Routine routine = (Routine) e.nextElement();
						routine.addBefore("BIT/SudokuTool", "dynMethodCount", new Integer(1));
                    
						for (Enumeration b = routine.getBasicBlocks().elements(); b.hasMoreElements(); ) {
							BasicBlock bb = (BasicBlock) b.nextElement();
							bb.addBefore("BIT/SudokuTool", "dynInstrCount", new Integer(bb.size()));
						}
					}
					ci.addAfter("BIT/SudokuTool", "printDynamic", classname);
					ci.write(out_filename);
				}
			}
		}

		public synchronized static List<Float> getNumber(){
			List<Float> values = new ArrayList();
		
			values.add(dyn_method_count);
			values.add(dyn_bb_count);
			values.add(dyn_instr_count);

			dyn_method_count = 0F;
			dyn_bb_count = 0F; 
			dyn_instr_count =0F;

			return values;
		}

	
    public static synchronized void printDynamic(String foo) throws Exception 
		{
			if (dyn_method_count == 0) {
				return;
			}
		
			float instr_per_bb = (float) dyn_instr_count / (float) dyn_bb_count;
			float instr_per_method = (float) dyn_instr_count / (float) dyn_method_count;
			float bb_per_method = (float) dyn_bb_count / (float) dyn_method_count;
			
		
		}
    
    public static synchronized void dynInstrCount(int incr) 
		{
			dyn_instr_count += incr;
			dyn_bb_count++;
		}

    public static synchronized void dynMethodCount(int incr) 
		{
			dyn_method_count++;
		}
			
	public static void main(String argv[]) 
		{
			if (argv.length != 2) {
				printUsage();
			}
			try {
				File in_dir = new File(argv[0]);
				File out_dir = new File(argv[1]);
				if (in_dir.isDirectory() && out_dir.isDirectory()) {
					doDynamic(in_dir, out_dir);
				}
				else {
					printUsage();
				}
			}
			catch (NullPointerException e) {
				printUsage();
			}
		}
}
