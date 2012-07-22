package wyil;

import java.io.*;
import java.util.Arrays;

import wybs.lang.SyntaxError;
import wybs.lang.SyntaxError.InternalFailure;
import wyil.io.WyilFilePrinter;
import wyil.io.WyilFileReader;
import wyil.lang.WyilFile;
import wyjvm.io.BinaryInputStream;
import wyjvm.io.BinaryOutputStream;

public class Main {
	
	public static PrintStream errout;
	
	/**
	 * Initialise the error output stream so as to ensure it will display
	 * unicode characters (when possible). Additionally, extract version
	 * information from the enclosing jar file.
	 */
	static {
		try {
			errout = new PrintStream(System.err, true, "UTF8");
		} catch(Exception e) {
			errout = System.err;
		}
	}
	
	public static void main(String[] args) {	
		boolean verbose = true;
		try {
			WyilFile wf = new WyilFileReader(args[0]).read();
			new WyilFilePrinter(System.out).apply(wf);
		} catch (InternalFailure e) {
			e.outputSourceError(System.err);
			if(verbose) {
				e.printStackTrace(errout);
			}
		} catch (SyntaxError e) {
			e.outputSourceError(errout);
			if (verbose) {
				e.printStackTrace(errout);
			}
		} catch (Throwable e) {
			errout.println("internal failure (" + e.getMessage() + ")");
			if (verbose) {
				e.printStackTrace(errout);
			}
		
		}		
	}
}