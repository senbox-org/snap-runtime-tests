# snap-runtime-tests
Tests for testing the runtime. General collection of test tools for runtime, performance 
or other demanding tests.

Below a description of the tools.

## ProductWriterPerformanceTester

Application to test the write performance of a product writer. 

It creates a large in-memory product and writes this to disk using a configurable writer class.
During the tests, a number of common cases is checked for performance. These are

* complete bands: all variables are written as one data block
* complete bands multithreaded: as case "complete bands" but a separate thread for each variable
* lines band sequential: writes linewise, bands are written sequentially
* lines band interleaved: writes linewise bands are written interleaved
* tiles band sequential: writes in tiles, bands are written sequentially
* tiles band interleaved: writes in tiles bands are written interleaved

The write times are recorded on the console. Optionally a content verification is 
done on a configurable number of random locations.

A configuration file as resource allows to configure parameters. Please copy "writer_tester_example.properties"
to "writer_tester.properties" and set your configuration.
 
