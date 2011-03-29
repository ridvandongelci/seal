package it.crs4.mr.read_sort;

import it.crs4.mr.read_sort.BwaRefAnnotation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;


import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;


public class MergeSortedAlignments extends Configured implements Tool
{
	private String userInput;
	private String userOutput;
	private String userAnnotation;

	private BwaRefAnnotation refAnnotation;

	private Path annotationPath;
	private Path[] inputPaths;
	private Path outputPath;

	private static final Log log = LogFactory.getLog(MergeSortedAlignments.class);

	private Path getQualifiedPath(String simplePath) throws IOException
	{
		Path path = new Path(simplePath);
		return path.makeQualified(path.getFileSystem(getConf()));
	}

	/**
	 * Scan command line and set configuration values appropriately.
	 * Calls System.exit in case of a command line error.
	 */
	private void scanOptions(String[] args)
	{
		Configuration conf = getConf();

		Option ann = OptionBuilder
			              .withDescription("annotation file (.ann) of the BWA reference used to create the SAM data")
			              .hasArg()
			              .withArgName("ref.ann")
										.withLongOpt("annotations")
			              .create("ann");
		Options options = new Options();
		options.addOption(ann);

		CommandLineParser parser = new GnuParser();

		try 
		{
			CommandLine line = parser.parse( options, args );

			if (line.hasOption("ann"))
				userAnnotation = line.getOptionValue("ann");
			else
				throw new ParseException("You must provide the path to the reference annotation file (<ref>.ann)");

			// remaining args
			String[] otherArgs = line.getArgs();
			if (otherArgs.length == 2) 
			{
				userInput = otherArgs[0];
				userOutput = otherArgs[1];
			}
			else
				throw new ParseException("You must provide HDFS input and output paths");
		}
		catch( ParseException e ) 
		{
			System.err.println("Usage error: " + e.getMessage());
			// XXX: redirect System.out to System.err since the simple version of 
			// HelpFormatter.printHelp prints to System.out, and we're on a way to
			// a fatal exit.
			System.setOut(System.err);
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( "MergeSortedAlignments -ann <ref>.ann <in> <out>", options);
			System.exit(1);
		}
	}

	private void loadAnnotations() throws IOException
	{
		Path annPath = getQualifiedPath(userAnnotation);
		FileSystem fs = annPath.getFileSystem(getConf());
		FSDataInputStream in = fs.open(annPath);
		refAnnotation = new BwaRefAnnotation(new InputStreamReader(in));
	}

	private static class SourcePathFilter implements org.apache.hadoop.fs.PathFilter
	{
		public boolean accept(Path p)
		{
			boolean decision = true;
			if (p.getName().toString().startsWith("_"))
				decision = false;

			return decision;
		}
	}

	private Path[] getSourcePaths() throws Exception
	{
		Path srcPath = new Path(userInput);
    FileSystem srcFs = srcPath.getFileSystem(getConf());
		if (srcFs.exists(srcPath))
		{
			FileStatus stat = srcFs.getFileStatus(srcPath);
			if (stat.isDir())
			{
				String msg = "source path " + srcPath + " is a directory.  Globbing with ";
				srcPath = new Path(srcPath, "*");
				log.info(msg + srcPath);
			}
		}

		// Glob source path.  The returned paths are already sorted.  We filter out paths starting 
		// with '_' (see SourcePathFilter).
		// If the path doesn't contain a glob patter, and it doesn't exist, the function will return null.
    Path[] sources = FileUtil.stat2Paths(srcFs.globStatus(srcPath, new SourcePathFilter()), srcPath);
		if (sources == null)
			throw new IllegalArgumentException("Source path doesn't exist on " + srcFs.getUri());

		if (log.isDebugEnabled())
		{
			log.debug("Sources:");
			for (int i = 0; i < sources.length; ++i)
				log.debug(sources[i]);
		}

		if (sources.length == 0)
			throw new IllegalArgumentException("no source files selected");
		return sources;
	}

	private void writeSamHeader(FSDataOutputStream rawOut) throws IOException
	{
		Writer out = 
			new BufferedWriter(
					new OutputStreamWriter(rawOut));
		out.write("@HD\tVN:1.0\tSO:coordinate\n");  // TODO: add support for name sorting

		for (BwaRefAnnotation.Contig c: refAnnotation)
			out.write( String.format("@SQ\tSN:%s\tLN:%d\n", c.getName(), c.getLength()) );

		out.write("@PG\tID:seal\n");
		out.flush();
	}

	public void copyMerge(Path[] sources, OutputStream out) throws IOException 
	{
		Configuration conf = getConf();
    
		for (int i = 0; i < sources.length; ++i)
		{
			FileSystem fs = sources[i].getFileSystem(conf);
			InputStream in = fs.open(sources[i]);
			try 
			{
				IOUtils.copyBytes(in, out, conf, false);
			}
			finally {
				in.close();
			} 
		}
	}


	public int run(String[] args) throws Exception
	{
		scanOptions(args);
		Configuration conf = getConf();

		Path[] sources = getSourcePaths();

		Path destPath = getQualifiedPath(userOutput);
		FileSystem destFs = destPath.getFileSystem(conf);
		if (destFs.exists(destPath))
			throw new RuntimeException("Output destination " + destPath + " exists.  Please remove it or change the output path.");

		loadAnnotations();
		log.info("Annotations read");
		log.info("Merging sources to " + destPath);

		FSDataOutputStream destFile = destFs.create(destPath);
		try
		{
			writeSamHeader(destFile);
			copyMerge(sources, destFile);
		}
		finally {
			destFile.close();
		}
		log.info("Finished");

		return 0;
	}

	public static void main(String[] args)
	{
		int res = 0;
		try
		{
			res = ToolRunner.run(new MergeSortedAlignments(), args);
		}
		catch (Exception e)
		{
			System.err.println("Error executing MergeSortedAlignments: " + e.getMessage());
			System.exit(1);
		}
	}
}