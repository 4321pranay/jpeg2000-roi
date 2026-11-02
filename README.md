# jpeg2000-roi

ImageIO writer that adds rectangular **region-of-interest (ROI)** encoding to JPEG 2000, on top of the
**unmodified** `com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0`.

```java
ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg2000-roi").next();
RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();
p.setLossless(false);
p.setEncodingRate(0.25);                         // bits per pixel for the whole image
p.setFilter(RoiJ2KImageWriteParam.FILTER_53);    // 5/3 lets the ROI become bit-exact
p.addROI(new Rectangle(500, 600, 260, 260));     // e.g. a QR code
try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
    w.setOutput(out);
    w.write(null, new IIOImage(image, null, null), p);
}
```

## How it works
- JPEG 2000 Part 1 "max-shift" ROI: coefficients inside the regions are coded before the background.
  The file carries an `RGN` marker; region positions are not stored, and every compliant decoder reads it.
- The standard `J2KImageWriter` builds its internal parameters without ROI and offers no hook, so
  `RoiJ2KImageWriter.writeWithRoi` repeats its encoding pipeline (copied from jai-imageio 1.4.0,
  Sun BSD licence retained) with the ROIs set. Without ROIs, calls go straight to the standard writer
  (byte-identical output, tested).
- Registered as `jpeg2000-roi` only: lookups for `jpeg2000`, `.jp2`, `image/jp2` still return the
  standard writer (tested).

## Rate vs ROI quality
The ROI is coded first. Until it is complete the background gets almost nothing, so pick a rate that
covers the ROI. Test page (800x1000, 260x260 QR), decoded with OpenJPEG:

| bpp  | QR region  | background | plain encoding: QR / background |
|------|------------|------------|---------------------------------|
| 0.08 | 26.2 dB    | 11.8 dB    | 14.2 / 21.3 dB                  |
| 0.15 | 43.3 dB    | 17.9 dB    | 18.9 / 23.6 dB                  |
| 0.25 | bit-exact  | 22.7 dB    | 19.8 / 26.5 dB                  |
| 0.40 | bit-exact  | 27.1 dB    | 21.8 / 29.5 dB                  |

## Decoder compatibility
| Decoder | Result |
|---|---|
| OpenJPEG (`opj_decompress`) | correct (tested) |
| Poppler, JPX image inside a PDF | correct (tested) |
| **jai-imageio reader** (`ImageIO.read`), and therefore **PDFBox** | **background lost**, ROI fine, even for lossless files (known bug, characterised by tests) |
| Acrobat, Chrome, Firefox, Edge, macOS Preview | not tested |

## Caveats
- Depends on jai-imageio internal classes: keep the dependency pinned to 1.4.0.
- Tested with 8-bit grey and RGB images.
- ROIs are in full-image coordinates. Combining them with `setSourceRegion` (a real crop) or
  `setSourceSubsampling` throws `IllegalArgumentException`: crop or scale the image first. JJ2000 itself
  silently ignores ROIs that miss the image, so this is checked up front, together with ROIs outside the image.

## Install
Not published to a Maven repository yet: clone and run `mvn install`, then depend on
`io.github.4321pranay:jpeg2000-roi:1.0.0-SNAPSHOT`.

## Tests
Kept in a separate repository: https://github.com/4321pranay/jpeg2000-roi-tests

## License
BSD 3-Clause (see `LICENSE`). Contains code adapted from jai-imageio-jpeg2000; its Sun BSD and JJ2000
notices are reproduced in `NOTICE`.
