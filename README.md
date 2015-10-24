Introduction
XDeltaEncoder is a tool for creating and manipulating binary delta patches. Binary delta is a file representing difference between old and new version of a binary file. With the use of the delta patch, new version of the binary file can be reconstructed (decoded) from the original old binary file.

XDeltaEncoder uses GDIFF patch format specified in http://www.w3.org/TR/NOTE-gdiff-19970901.html

XDeltaEncoder is based on modified Javaxdelta libraries. For more information about Javaxdelta project please visit http://javaxdelta.sourceforge.net.

Usage
=====
Create delta from source and target
java -Xmx2048m -jar XDeltaEncoder.jar [options] source target delta
When source is very large - several Gb, use e.g.:
java -Xmx1500m -jar XDeltaEncoder.jar -p -b 500m -tb 500m -bt 5m -c 20 source target delta
Decode target from source and delta
java -jar XDeltaEncoder.jar -d [options] source delta target
Verify created delta against existing target
java -jar XDeltaEncoder.jar -v [options] source delta target
Merge two consecutive delta files
From the setting like this:

file1 --> delta1 --> file2 --> delta2 --> file3

delta1 and delta2 are merged to new merged delta so as file3 can be decoded directly from file1:

file1 --> merged --> file3

java -jar XDeltaEncoder.jar -m [options] delta1 delta2 merged
Consistency check between delta1 and delta2 is NOT made.

Reverse delta
Reverse delta is defined from the above setting as follows:

delta1 and delta2 are merged to merged
merged and delta2 are unpacked to obtain raw deltas
revdelta is computed between unpacked delta2 and merged so as delta2(unpacked) -> revdelta -> merged(unpacked)
Decoding using reverse delta consists of following steps:

unpack delta2
decode unpacked merged from unpacked delta2 and revdelta
decode file3 using file1 and merged (using -ng option).
So, when revdelta is created, file3 can be decoded using file1, delta2 and revdelta.

Compute reverse delta from delta1 and delta2:

java -Xmx2048m -jar XDeltaEncoder.jar [options] -r revdelta delta1 delta2
Decode file3 using reverse delta, file1 and delta2:

java -Xmx2048m -jar XDeltaEncoder.jar [options] -d -r revdelta file1 delta2 file3
Upgrade reverse delta:

From the setting

file1 -> delta1 -> file2 -> delta2 -> file3 -> delta3 -> file4

with revdelta computing file3 from revdelta, file1 and delta2

compute revdelta2

java -Xmx2048m -jar XDeltaEncoder.jar [options] -r revdelta2 -u revdelta delta2 delta3
so as file4 can be computed from revdelta2, file1 and delta3:

When differences between file1, file2, file3 ... filen are not very significant, storing matrix of reverse deltas together with last delta makes upgrading to filen from any previous version two-step process regardless of the age of the source file. In addition, reverse deltas are usually significantly smaller than merged forward deltas because they are based on the last destination file change.

Options
=======
Encode options: 
         -c chunksize     start chunk size in bytes
         -b blocksize     block size processed in 1 pass in bytes - default 128m
         -t               test the best block size (deprecated)
         -p               preprocess using full file size (can significantly speed up
                          processing of very large files)
         -f               read source block from file in memory
                          slower but needs less memory
         -tb blocksize    block size of target
         -bt size         threshold to trigger source block processing
                          size represents matched bytes from pre-processing step.
                          Used only in conjunction with all of the following switches:
                          -p -b -tb
Decode options:
         -so              split output - useful when JVM cannot handle big files
         -mo              merge splitted output when finished (Linux only)
         -jd              join delta from splitted parts - delta means delta prefix
         -ng              delta is not gzipped
         -g               join source from splitted parts - source means source prefix
Help and support
================
Run the software without parameters to get help:

java -jar XDeltaEncoder.jar

You are welcome to submit a bug report or suggest an enhancement at the Issues page.

Java developers are invited to create a clone of the project repository and propose their contributions for merging to the project.
