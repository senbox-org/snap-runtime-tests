package org.esa.snap.test.runtime;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductWriter;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.StopWatch;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ProductWriterPerformanceTester {

    private static int TILE_WIDTH_DEFAULT = 512;
    private static int TILE_HEIGHT_DEFAULT = 512;

    private static int RASTER_WIDTH_DEFAULT = 8000;
    private static int RASTER_HEIGHT_DEFAULT = 12000;

    private String writerFormat;
    private String readerFormat;
    private int rasterWidth;
    private int rasterHeight;
    private int tileWidth;
    private int tileHeight;

    private Properties properties;

    /**
     * Executes a Product writer performance test and dumps the result to the console window.
     * <p>
     * This test creates an in-memory product and writes it to disk using different strategies.
     *
     * @param args commandline arguments
     *             -w <format-name> : the writer format name
     *             -r <format-name> : the reader format name (used for verification)
     * @throws IOException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException, ParseException {
        final Options options = createOptions();
        final PosixParser parser = new PosixParser();
        final CommandLine commandLine = parser.parse(options, args);

        final ProductWriterPerformanceTester tester = new ProductWriterPerformanceTester();
        tester.run(commandLine);
    }

    private static void cleanUpandPrepareForNext(File testDir) throws IOException, InterruptedException {
        if (!FileUtils.deleteTree(testDir)) {
            throw new IOException("unable to delete test dir");
        }

        Thread.sleep(100);

        if (!testDir.mkdirs()) {
            throw new IOException("unable to create test dir");
        }
    }

    private static float[] createFloatArray(int rasterWidth, int rasterHeight) {
        final float[] floats = new float[rasterWidth * rasterHeight];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = i;
        }
        return floats;
    }

    private static Options createOptions() {
        final Options options = new Options();

        OptionBuilder.hasArg();
        OptionBuilder.withArgName("writer-format");
        OptionBuilder.withDescription("The product writer format name to be tested.");
        OptionBuilder.isRequired();
        options.addOption(OptionBuilder.create("w"));

        OptionBuilder.hasArg();
        OptionBuilder.withArgName("reader-format");
        OptionBuilder.withDescription("The product reader format name used for verification. If left empty, no verification is performed.");
        options.addOption(OptionBuilder.create("r"));

        return options;
    }

    private Product createProduct() {
        final Product product = new Product("test", "perf-test", rasterWidth, rasterHeight);

        for (int i = 0; i < 5; i++) {
            final Band floatBand = new Band("float_" + i, ProductData.TYPE_FLOAT32, rasterWidth, rasterHeight);
            floatBand.setRasterData(ProductData.createInstance(createFloatArray(rasterWidth, rasterHeight)));
            floatBand.setNoDataValue(Float.NaN);
            floatBand.setNoDataValueUsed(true);
            product.addBand(floatBand);
        }

        return product;
    }

    private File prepareFileSystem() throws IOException {
        final String target_dir = properties.getProperty("target_dir");
        if (StringUtils.isNullOrEmpty(target_dir)) {
            throw new IllegalArgumentException("Target directory not defined.");
        }
        final File testDir = new File(target_dir);
        if (!testDir.mkdirs()) {
            throw new IOException("unable to create test dir: " + target_dir);
        }
        return testDir;
    }

    private File write_band(Product product, File testDir) throws IOException {
        final ProductWriter writer = createWriter();
        final String dotExtension = writer.getWriterPlugIn().getDefaultFileExtensions()[0];
        final File targetFile = new File(testDir, "test" + dotExtension);

        final StopWatch stopWatch = new StopWatch();

        writer.writeProductNodes(product, targetFile);

        final Band[] bands = product.getBands();
        for (final Band band : bands) {
            writer.writeBandRasterData(band, 0, 0, product.getSceneRasterWidth(), product.getSceneRasterHeight(), band.getData(),
                                       ProgressMonitor.NULL);
        }
        writer.flush();
        writer.close();

        stopWatch.stop();
        System.out.println("write bands                  " + stopWatch.getTimeDiffString());

        return targetFile;
    }

    private File write_multithreaded_band(Product product, File testDir) throws IOException, ExecutionException, InterruptedException {
        final ProductWriter writer = createWriter();
        final String dotExtension = writer.getWriterPlugIn().getDefaultFileExtensions()[0];
        final File targetFile = new File(testDir, "test_mt" + dotExtension);

        final StopWatch stopWatch = new StopWatch();

        writer.writeProductNodes(product, targetFile);

        final Band[] bands = product.getBands();
        ExecutorService service = Executors.newFixedThreadPool(bands.length);
        List<Future<Runnable>> futures = new ArrayList<>();
        for (final Band band : bands) {
            final Runnable runnable = () -> {
                try {
                    writer.writeBandRasterData(band, 0, 0, product.getSceneRasterWidth(), product.getSceneRasterHeight(), band.getData(),
                                               ProgressMonitor.NULL);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            };

            final Future future = service.submit(runnable);
            futures.add(future);
        }

        for (Future<Runnable> f : futures) {
            f.get();
        }

        service.shutdownNow();

        writer.flush();
        writer.close();

        stopWatch.stop();
        System.out.println("write bands multi            " + stopWatch.getTimeDiffString());

        return targetFile;
    }

    private File write_lines_band_sequential(Product product, File testDir) throws IOException {
        final ProductWriter writer = createWriter();
        final String dotExtension = writer.getWriterPlugIn().getDefaultFileExtensions()[0];
        final File targetFile = new File(testDir, "test_per_line_band" + dotExtension);

        final int sceneRasterWidth = product.getSceneRasterWidth();
        final int sceneRasterHeight = product.getSceneRasterHeight();

        final StopWatch stopWatch = new StopWatch();

        writer.writeProductNodes(product, targetFile);

        final Band[] bands = product.getBands();
        for (final Band band : bands) {
            final float[] rawData = (float[]) band.getRasterData().getElems();

            final float[] lineData = new float[sceneRasterWidth];
            for (int line = 0; line < sceneRasterHeight; line++) {
                System.arraycopy(rawData, line * sceneRasterWidth, lineData, 0, sceneRasterWidth);
                writer.writeBandRasterData(band, 0, line, sceneRasterWidth, 1, ProductData.createInstance(lineData),
                                           ProgressMonitor.NULL);
            }
        }
        writer.flush();
        writer.close();

        stopWatch.stop();
        System.out.println("write line band sequential   " + stopWatch.getTimeDiffString());

        return targetFile;
    }

    private File write_lines_band_interleaved(Product product, File testDir) throws IOException {
        final ProductWriter writer = createWriter();
        final String dotExtension = writer.getWriterPlugIn().getDefaultFileExtensions()[0];
        final File targetFile = new File(testDir, "test_line_band_inter" + dotExtension);

        final int sceneRasterWidth = product.getSceneRasterWidth();
        final int sceneRasterHeight = product.getSceneRasterHeight();

        final StopWatch stopWatch = new StopWatch();

        writer.writeProductNodes(product, targetFile);

        final Band[] bands = product.getBands();
        final float[] lineData = new float[sceneRasterWidth];
        for (int line = 0; line < sceneRasterHeight; line++) {
            for (final Band band : bands) {
                final float[] rawData = (float[]) band.getRasterData().getElems();
                System.arraycopy(rawData, line * sceneRasterWidth, lineData, 0, sceneRasterWidth);
                writer.writeBandRasterData(band, 0, line, sceneRasterWidth, 1, ProductData.createInstance(lineData),
                                           ProgressMonitor.NULL);
            }
        }
        writer.flush();
        writer.close();

        stopWatch.stop();
        System.out.println("write line band interleaved  " + stopWatch.getTimeDiffString());

        return targetFile;
    }

    private ProductWriter createWriter() {
        return ProductIO.getProductWriter(writerFormat);
    }

    private File write_tiles_band_sequential(Product product, File testDir) throws IOException {
        final ProductWriter writer = createWriter();
        final String dotExtension = writer.getWriterPlugIn().getDefaultFileExtensions()[0];
        final File targetFile = new File(testDir, "test_tiles_per_band" + dotExtension);

        final int sceneRasterWidth = product.getSceneRasterWidth();
        final int sceneRasterHeight = product.getSceneRasterHeight();

        final int numTilesH = sceneRasterWidth / tileWidth + 1;
        final int numTilesV = sceneRasterHeight / tileHeight + 1;
        final float[] tileData = new float[tileWidth * tileHeight];

        final StopWatch stopWatch = new StopWatch();

        writer.writeProductNodes(product, targetFile);

        final Band[] bands = product.getBands();

        final int lastLineIndex = numTilesH - 1;
        final int lastRowIndex = numTilesV - 1;

        for (final Band band : bands) {
            final float[] rawData = (float[]) band.getRasterData().getElems();

            for (int tileRow = 0; tileRow < numTilesV; tileRow++) {
                int writeOffsetY = tileRow * tileHeight;

                int currTileHeight = tileHeight;
                if (tileRow == lastRowIndex) {
                    currTileHeight = sceneRasterHeight - (lastRowIndex * currTileHeight);
                }

                final int tileRowOffset = writeOffsetY * sceneRasterWidth;
                for (int tileLine = 0; tileLine < numTilesH; tileLine++) {
                    final int offsetX = tileLine * tileWidth;

                    float[] data = tileData;
                    int currTileWidth = this.tileWidth;
                    if (tileLine == lastLineIndex) {
                        currTileWidth = sceneRasterWidth - (lastLineIndex * tileWidth);
                        if (currTileWidth == 0) {
                            continue;
                        }
                    }
                    if (tileLine == lastLineIndex || tileRow == lastRowIndex) {
                        data = new float[currTileWidth * currTileHeight];
                    }

                    for (int line = 0; line < currTileHeight; line++) {
                        int srcPos = offsetX + line * sceneRasterWidth + tileRowOffset;
                        int destPos = line * currTileWidth;
                        System.arraycopy(rawData, srcPos, data, destPos, currTileWidth);
                    }
                    writer.writeBandRasterData(band, offsetX, writeOffsetY, currTileWidth, currTileHeight, ProductData.createInstance(data), ProgressMonitor.NULL);
                }
            }
        }
        writer.flush();
        writer.close();

        stopWatch.stop();
        System.out.println("write tiles band sequential  " + stopWatch.getTimeDiffString());

        return targetFile;
    }

    private File write_tiles_band_interleaved(Product product, File testDir) throws IOException {
        final ProductWriter writer = createWriter();
        final String dotExtension = writer.getWriterPlugIn().getDefaultFileExtensions()[0];
        final File targetFile = new File(testDir, "test_tiles_band_inter" + dotExtension);

        final int sceneRasterWidth = product.getSceneRasterWidth();
        final int sceneRasterHeight = product.getSceneRasterHeight();

        final int numTilesH = sceneRasterWidth / tileWidth + 1;
        final int numTilesV = sceneRasterHeight / tileHeight + 1;
        final float[] tileData = new float[tileWidth * tileHeight];

        final StopWatch stopWatch = new StopWatch();

        writer.writeProductNodes(product, targetFile);

        final Band[] bands = product.getBands();

        final int lastLineIndex = numTilesH - 1;
        final int lastRowIndex = numTilesV - 1;

        for (int tileRow = 0; tileRow < numTilesV; tileRow++) {
            int writeOffsetY = tileRow * tileHeight;

            int currTileHeight = tileHeight;
            if (tileRow == lastRowIndex) {
                currTileHeight = sceneRasterHeight - (lastRowIndex * tileHeight);
            }

            final int tileRowOffset = writeOffsetY * sceneRasterWidth;
            for (int tileLine = 0; tileLine < numTilesH; tileLine++) {
                for (final Band band : bands) {
                    final float[] rawData = (float[]) band.getRasterData().getElems();
                    final int offsetX = tileLine * tileWidth;

                    int currTileWidth = tileWidth;
                    float[] data = tileData;
                    if (tileLine == lastLineIndex) {
                        currTileWidth = sceneRasterWidth - (lastLineIndex * tileWidth);
                        if (currTileWidth == 0) {
                            continue;
                        }
                    }
                    if (tileLine == lastLineIndex || tileRow == lastRowIndex) {
                        data = new float[currTileWidth * currTileHeight];
                    }

                    for (int line = 0; line < currTileHeight; line++) {
                        int srcPos = offsetX + line * sceneRasterWidth + tileRowOffset;
                        int destPos = line * currTileWidth;
                        System.arraycopy(rawData, srcPos, data, destPos, currTileWidth);
                    }

                    writer.writeBandRasterData(band, offsetX, writeOffsetY, currTileWidth, currTileHeight, ProductData.createInstance(data), ProgressMonitor.NULL);
                }
            }
        }
        writer.flush();
        writer.close();

        stopWatch.stop();
        System.out.println("write tiles band interleaved " + stopWatch.getTimeDiffString());

        return targetFile;
    }

    private void run(CommandLine cmdLine) throws IOException, InterruptedException, ExecutionException {
        initialize(cmdLine);

        final File testDir = prepareFileSystem();
        final Product product = createProduct();

        try {
            File targetFile = write_band(product, testDir);
            assertContent(product, targetFile);

            cleanUpandPrepareForNext(testDir);

            targetFile = write_multithreaded_band(product, testDir);
            assertContent(product, targetFile);

            cleanUpandPrepareForNext(testDir);

            targetFile = write_lines_band_sequential(product, testDir);
            assertContent(product, targetFile);

            cleanUpandPrepareForNext(testDir);

            targetFile = write_lines_band_interleaved(product, testDir);
            assertContent(product, targetFile);

            cleanUpandPrepareForNext(testDir);

            targetFile = write_tiles_band_sequential(product, testDir);
            assertContent(product, targetFile);

            targetFile = write_tiles_band_interleaved(product, testDir);
            assertContent(product, targetFile);
        } finally {
            product.dispose();
            FileUtils.deleteTree(testDir);
        }
    }

    private void initialize(CommandLine cmdLine) throws IOException {
        writerFormat = cmdLine.getOptionValue("w");
        readerFormat = cmdLine.getOptionValue("r");

        System.out.println("writerFormat = " + writerFormat);
        System.out.println("readerFormat = " + readerFormat);

        final InputStream propertiesStream = ProductWriterPerformanceTester.class.getResourceAsStream("writer_tester.properties");
        properties = new Properties();
        properties.load(propertiesStream);
        propertiesStream.close();

        rasterWidth = RASTER_WIDTH_DEFAULT;
        final String rasterWidthProp = properties.getProperty("raster_width");
        if (StringUtils.isNotNullAndNotEmpty(rasterWidthProp)) {
            rasterWidth = Integer.parseInt(rasterWidthProp.trim());
        }

        rasterHeight = RASTER_HEIGHT_DEFAULT;
        final String rasterHeightProp = properties.getProperty("raster_height");
        if (StringUtils.isNotNullAndNotEmpty(rasterHeightProp)) {
            rasterHeight = Integer.parseInt(rasterHeightProp.trim());
        }

        tileWidth = TILE_WIDTH_DEFAULT;
        final String tileWidthProp = properties.getProperty("tile_width");
        if (StringUtils.isNotNullAndNotEmpty(tileWidthProp)) {
            tileWidth = Integer.parseInt(tileWidthProp.trim());
        }

        tileHeight = TILE_HEIGHT_DEFAULT;
        final String tileHeightProp = properties.getProperty("tile_height");
        if (StringUtils.isNotNullAndNotEmpty(tileHeightProp)) {
            tileHeight = Integer.parseInt(tileHeightProp.trim());
        }

        System.out.println("raster width  = " + rasterWidth);
        System.out.println("raster height = " + rasterHeight);

        System.out.println("tile width    = " + tileWidth);
        System.out.println("tile height   = " + tileHeight);
        System.out.println("--------------------------");
    }

    private void assertContent(Product referenceProduct, File targetFile) throws IOException {
        if (StringUtils.isNullOrEmpty(readerFormat)) {
            return;
        }

        final int width = referenceProduct.getSceneRasterWidth();
        final int height = referenceProduct.getSceneRasterHeight();
        final Product product = ProductIO.readProduct(targetFile, readerFormat);
        if (product == null) {
            throw new IOException("Unable to read file: " + targetFile.getAbsolutePath());
        }

        try {
            final Band[] bands = referenceProduct.getBands();
            int bandIndex = 0;

            for (final Band referenceBand : bands) {
                final Band band = product.getBandAt(bandIndex);
                final ProductData expectedBuffer = ProductData.createInstance(new float[1]);
                final ProductData actualBuffer = ProductData.createInstance(new float[1]);

                for (int i = 0; i < 100; i++) {
                    final int x = (int) Math.floor(Math.random() * width);
                    final int y = (int) Math.floor(Math.random() * height);

                    referenceBand.readRasterData(x, y, 1, 1, expectedBuffer, ProgressMonitor.NULL);
                    band.readRasterData(x, y, 1, 1, actualBuffer, ProgressMonitor.NULL);

                    final float expected = expectedBuffer.getElemFloat();
                    final float actual = actualBuffer.getElemFloat();
                    if (Math.abs(expected - actual) > 1e-8) {
                        throw new IllegalStateException("Value mismatch at (x,y): (" + x + " ," + y + ") exp: " + expected + " act: " + actual);
                    }
                }
                bandIndex++;
            }
            System.out.println("verified OK");
        } finally {
            product.dispose();
        }
    }
}
