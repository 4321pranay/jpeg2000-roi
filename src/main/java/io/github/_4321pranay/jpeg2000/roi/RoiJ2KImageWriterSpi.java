package io.github._4321pranay.jpeg2000.roi;

import com.github.jaiimageio.jpeg2000.impl.J2KImageWriterSpi;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import java.util.Locale;

/**
 * Registers {@link RoiJ2KImageWriter} with ImageIO under the format name {@code "jpeg2000-roi"}.
 *
 * <p>It deliberately registers no file suffixes or MIME types and a distinct format name, so lookups
 * for {@code "jpeg2000"}, {@code "jp2"} or {@code image/jp2} keep returning the standard writer.
 */
public class RoiJ2KImageWriterSpi extends ImageWriterSpi {

    public static final String FORMAT_NAME = "jpeg2000-roi";

    private static final J2KImageWriterSpi STANDARD = new J2KImageWriterSpi();

    public RoiJ2KImageWriterSpi() {
        super("io.github.4321pranay",
              "1.0",
              new String[] {FORMAT_NAME, "JPEG2000-ROI"},
              null,                                   // no suffixes: don't shadow the standard writer
              null,                                   // no MIME types
              RoiJ2KImageWriter.class.getName(),
              new Class<?>[] {javax.imageio.stream.ImageOutputStream.class},
              new String[] {"com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi"},
              false, null, null, null, null,
              true,
              "com_sun_media_imageio_plugins_jpeg2000_image_1.0",
              "com.github.jaiimageio.jpeg2000.impl.J2KMetadataFormat",
              null, null);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return STANDARD.canEncodeImage(type);
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new RoiJ2KImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG 2000 writer with region-of-interest (max-shift) support";
    }
}
