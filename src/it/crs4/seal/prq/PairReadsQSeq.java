/*
 * Copyright (C) 2011 CRS4.
 * 
 * This file is part of Seal.
 * 
 * Seal is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * 
 * Seal is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with Seal.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.crs4.seal.prq;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.util.GenericOptionsParser;

import it.crs4.seal.prq.SequenceId;

/**
 * This is a Java implementation of the pair_reads_qseq application.  Its general purpose
 * is to preprocess sequence fragments output from the base calling procedure so
 * that it may be fed to the alignment process.  In detail, at the moment it matches
 * separate read from the same location in the flowcell (head and tail sections of a
 * single DNA fragment) and puts them in a single output record so that they may be
 * more easily aligned.
 *
 * The algorithm is analogous to the one used in the SecondarySort Hadoop example.
 * We use Hadoop to sort all read sections by their location on the flow cell and
 * read number.  We group fragments by only their location on the flow cell, so that
 * all reads are presented to the reducer together, and they may be output as a 
 * single record.
 *
 * Run with: hadoop jar PairReadsQSeq.jar /user/pireddu/input /user/pireddu/output
 */
public class PairReadsQSeq
{
	public static enum ReadCounters {
		NotEnoughBases,
		FailedFilter,
		Dropped
	}

	/**
	 * Partition based only on the sequence location.
	 */
	public static class FirstPartitioner extends Partitioner<SequenceId,Text>
	{
		@Override
		public int getPartition(SequenceId key, Text value, int numPartitions) 
		{
			return Math.abs(key.getLocation().hashCode()) % numPartitions;
		}
	}

	/**
	 * Group based only on the sequence location.
	 */
	public static class GroupByLocation implements RawComparator<SequenceId>
	{
		@Override
		public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {

			int sizeVint1 = WritableUtils.decodeVIntSize(b1[s1]);
			int sizeVint2 = WritableUtils.decodeVIntSize(b2[s2]);

			int retval = WritableComparator.compareBytes(b1, s1+sizeVint1, l1-sizeVint1-Byte.SIZE/8, // Byte.SIZE is the byte size in bits
			                                             b2, s2+sizeVint2, l2-sizeVint2-Byte.SIZE/8);
			return retval;
		}

		@Override
		public int compare(SequenceId s1, SequenceId s2) {
			return s1.getLocation().compareTo(s2.getLocation());
		}
	}

	public static class FragmentMapper extends Mapper<LongWritable, Text, SequenceId, Text>
	{
		private static final int LINE_SIZE = 400;

		private SequenceId sequenceKey = new SequenceId();
		private Text sequenceValue = new Text();
		private StringBuilder builder;

		@Override
		public void setup(Context context)
		{
			builder = new StringBuilder(LINE_SIZE);
		}

		private void clearBuilder()
		{
			builder.delete(0, builder.length());
		}

		@Override
		public void map(LongWritable key, Text fragment, Context context)
			throws IOException, InterruptedException
		{
			String[] fields = fragment.toString().split("\t");
			if (fields.length != 11)
				throw new FileFormatException("mapper found " + fields.length + " fields instead of 11!");

			if (!fields[10].equals("1") && !fields[10].equals("0"))
				throw new FileFormatException("Invalid value in filter column: " + fragment.toString());

			// build the key
			clearBuilder();
			builder.append(fields[0]).append("_").append(fields[1]);
			for (int i = 2; i <= 5; ++i)
				builder.append(":").append(fields[i]);

			// finally the index field (6)
			builder.append("#").append(fields[6]);

			// field up and including the index number goes in the location.  The read is on its own.
			sequenceKey.set(builder.toString(), Integer.parseInt(fields[7]));

			// then the tab-delimited value
			clearBuilder();
			builder.append( fields[8].replace('.', 'N') ).append("\t").append(fields[9]).append("\t").append(fields[10]);
			sequenceValue.set(builder.toString());

			context.write(sequenceKey, sequenceValue);
			context.progress();
		}
	}

	public static class PairReducer extends Reducer<SequenceId,Text,Text,Text> 
	{
		public static final int DefaultMinBasesThreshold = 30;
		public static final String DefaultMinBasesThresholdConfigName = "bl.prq.min-bases-per-read";
		public static final boolean DropFailedFilterDefault = true;
		public static final String DropFailedFilterConfigName = "bl.prq.drop-failed-filter";
		public static final char UnknownBase = 'N';

		private static final byte[] delimByte = { 9 }; // tab character
		private static final String delim = "\t";

		private Text outputKey = new Text();
		private Text outputValue = new Text();
		int minBasesThreshold = 0;
		boolean dropFailedFilter = true;

		@Override
		public void setup(Context context)
		{
			minBasesThreshold = context.getConfiguration().getInt(DefaultMinBasesThresholdConfigName, DefaultMinBasesThreshold);
			dropFailedFilter = context.getConfiguration().getBoolean(DropFailedFilterConfigName, DropFailedFilterDefault);
			// create counters with a value of 0.
			context.getCounter(ReadCounters.NotEnoughBases);
			context.getCounter(ReadCounters.FailedFilter);
			context.getCounter(ReadCounters.Dropped);
		}

		private int[] findFields(Text read)
		{

			int[] fieldsPos = new int[3];
			fieldsPos[0] = 0;

			for (int i = 1; i <= 2; ++i)
			{
				fieldsPos[i] = read.find(delim, fieldsPos[i-1]) + 1; // +1 since we get the position of the delimiter
				if (fieldsPos[i] <= 0)
					throw new RuntimeException("invalid read/quality format: " + read.toString());
			}

			return fieldsPos;
		}

		@Override
		public void reduce(SequenceId key, Iterable<Text> values, Context context)
			throws IOException, InterruptedException 
		{
			outputKey.set( key.getLocation() );

			outputValue.clear();

			int nReads = 0;
			int nBadReads = 0;
			for (Text read: values)
			{
				++nReads;
				int[] fieldsPos = findFields(read);
				// filtered read?
				// If dropFailedFilter is false it shortcuts the test and sets filterPassed directly to true.
				// If it's true then we check whether the field is equal to '1'
				boolean filterPassed = !dropFailedFilter || read.getBytes()[fieldsPos[2]] == (byte)'1';

				if (!filterPassed)
				{
					context.getCounter(ReadCounters.FailedFilter).increment(1);
					++nBadReads;
				}
				else if (!checkReadQuality(read, fieldsPos))
				{
					context.getCounter(ReadCounters.NotEnoughBases).increment(1);
					++nBadReads;
				}

				if (nReads > 1)
					outputValue.append(delimByte, 0, delimByte.length);

				outputValue.append(read.getBytes(), 0, fieldsPos[2] - 1); // -1 so we exclude the last delimiter
			}

			if (nReads != 2)
				throw new RuntimeException("reducer didn't get a pair for key " + key.toString() + "(got " + nReads + ")");

			if (nBadReads < nReads) // if they're not all bad
				context.write(outputKey, outputValue);
			else
				context.getCounter(ReadCounters.Dropped).increment(nReads);
			
			context.progress();
		}

		/**
		 * Verify whether a read satisfies quality standards.
		 * For now this method verifies whether the read has at least 
		 * minBasesThreshold known bases (ignoring unknown bases N).
		 */
		protected boolean checkReadQuality(Text read, int[] fieldsPos)
		{
			/* The read's delimiter is at the bytes before the second field starts */
			int readEnd = fieldsPos[1] - 1;

			// The condition is "min number of valid bases".  However, we consider 
			// the inverse condition "max number of unknowns".
			// readEnd is also the length of the read fragment
			// readEnd - minBasesThreshold gives us the maximum number of unknowns acceptable.
			int nAcceptableUnknowns = readEnd - minBasesThreshold;

			if (nAcceptableUnknowns < 0) // the fragment is shorter than minBasesThreshold
				return false;

			int nUnknownBases = 0;
			byte[] data = read.getBytes(); // we can work directly in bytes as long as we only has ASCII characters
			for (int pos = 0; pos < readEnd; ++pos)
			{
				if (data[pos] == UnknownBase)
				{
					++nUnknownBases;
					if (nUnknownBases > nAcceptableUnknowns)
						return false;
				}
			}
			return true;
		}
	}

	public static void main(String[] args) throws Exception
	{
		Configuration conf = new Configuration();
		String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
		if (otherArgs.length != 2) {
			System.err.println("Usage: PairReadsQSeq <in> <out>");
			System.exit(2);
		}

		Job job = new Job(conf, "PairReadsQSeq " + otherArgs[0]);
		job.setJarByClass(PairReadsQSeq.class);

		job.setMapperClass(FragmentMapper.class);
		job.setMapOutputKeyClass(SequenceId.class);
		job.setMapOutputValueClass(Text.class);

		job.setPartitionerClass(FirstPartitioner.class);
		job.setGroupingComparatorClass(GroupByLocation.class);

		job.setReducerClass(PairReducer.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);

		FileInputFormat.addInputPath(job, new Path(otherArgs[0]));
		FileOutputFormat.setOutputPath(job, new Path(otherArgs[1]));

		System.exit(job.waitForCompletion(true) ? 0 : 1);
	}
}