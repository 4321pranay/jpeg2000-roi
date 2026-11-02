/*
 * The encoding pipeline in writeWithRoi() is adapted from
 * com.github.jaiimageio.jpeg2000.impl.J2KImageWriter#write (jai-imageio-jpeg2000 1.4.0):
 *
 * Copyright (c) 2005 Sun Microsystems, Inc. All Rights Reserved.
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 * - Redistribution of source code must retain the above copyright notice, this list of conditions
 *   and the following disclaimer.
 * - Redistribution in binary form must reproduce the above copyright notice, this list of
 *   conditions and the following disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 * Neither the name of Sun Microsystems, Inc. or the names of contributors may be used to endorse or
 * promote products derived from this software without specific prior written permission.
 * This software is provided "AS IS," without a warranty of any kind. ALL EXPRESS OR IMPLIED
 * CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN MICROSYSTEMS, INC.
 * ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF
 * USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR ITS
 * LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL,
 * CONSEQUENTIAL, INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF
 * LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF SUN HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * You acknowledge that this software is not designed or intended for use in the design,
 * construction, operation or maintenance of any nuclear facility.
 */
package io.github._4321pranay.jpeg2000.roi;

import com.github.jaiimageio.impl.common.ImageUtil;
import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriteParamJava;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriter;
import com.github.jaiimageio.jpeg2000.impl.J2KMetadata;
import com.github.jaiimageio.jpeg2000.impl.RenderedImageSrc;
import jj2000.j2k.codestream.writer.FileCodestreamWriter;
import jj2000.j2k.codestream.writer.HeaderEncoder;
import jj2000.j2k.entropy.encoder.EntropyCoder;
import jj2000.j2k.entropy.encoder.PostCompRateAllocator;
import jj2000.j2k.fileformat.writer.FileFormatWriter;
import jj2000.j2k.image.ImgDataConverter;
import jj2000.j2k.image.Tiler;
import jj2000.j2k.image.forwcomptransf.ForwCompTransf;
import jj2000.j2k.quantization.quantizer.Quantizer;
import jj2000.j2k.roi.encoder.ROIScaler;
import jj2000.j2k.util.CodestreamManipulator;
import jj2000.j2k.wavelet.analysis.ForwardWT;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * JPEG 2000 writer with rectangular region-of-interest support, built on the unmodified
 * jai-imageio-jpeg2000 library.
 *
 * <p>Use it like the standard writer, with a {@link RoiJ2KImageWriteParam}:
 * <pre>{@code
 * ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg2000-roi").next();
 * RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();
 * p.setLossless(false);
 * p.setEncodingRate(0.1);
 * p.addROI(new Rectangle(1100, 1430, 460, 460));
 * w.setOutput(ImageIO.createImageOutputStream(file));
 * w.write(null, new IIOImage(image, null, null), p);
 * }</pre>
 *
 * <p>Without ROIs, every call is passed unchanged to the standard {@link J2KImageWriter}. With ROIs,
 * the standard encoding pipeline runs with the ROIs set; the standard writer cannot do this itself
 * because it builds its internal parameters without them.
 *
 * <p><b>Known limitation of the library:</b> jai-imageio's own JPEG 2000 reader (and therefore
 * PDFBox) decodes the background of ROI-encoded images incorrectly. Decode with OpenJPEG or another
 * compliant decoder.
 */
public class RoiJ2KImageWriter extends J2KImageWriter {

    public RoiJ2KImageWriter(ImageWriterSpi originator) {
        super(originator);
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new RoiJ2KImageWriteParam(getLocale());
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param)
            throws IOException {
        if (param instanceof RoiJ2KImageWriteParam && ((RoiJ2KImageWriteParam) param).hasROIs()) {
            writeWithRoi(image, (RoiJ2KImageWriteParam) param);
        } else {
            super.write(streamMetadata, image, param);
        }
    }

    /**
     * ROIs are given in the coordinates of the full source image. A source region (crop) or
     * subsampling (shrink) changes the coordinates of the encoded image, so the ROIs would no longer
     * line up, and JJ2000 silently ignores ROIs that miss the image. Both cases are rejected rather
     * than producing a file that looks ROI-encoded but does not protect the region.
     */
    private static void validateRoiSettings(RoiJ2KImageWriteParam param, int imgW, int imgH) {
        Rectangle region = param.getSourceRegion();
        if (region != null && !region.contains(new Rectangle(imgW, imgH))) {
            throw new IllegalArgumentException("ROIs cannot be combined with a source region (" + region
                    + "); crop the image before encoding and give ROIs in the cropped image's coordinates");
        }
        if (param.getSourceXSubsampling() != 1 || param.getSourceYSubsampling() != 1
                || param.getSubsamplingXOffset() != 0 || param.getSubsamplingYOffset() != 0) {
            throw new IllegalArgumentException("ROIs cannot be combined with source subsampling ("
                    + param.getSourceXSubsampling() + "x" + param.getSourceYSubsampling()
                    + "); scale the image before encoding and give ROIs in the scaled image's coordinates");
        }
        Rectangle bounds = new Rectangle(imgW, imgH);
        for (Rectangle r : param.getROIs()) {
            if (!bounds.contains(r)) {
                throw new IllegalArgumentException("ROI " + r + " is outside the image " + imgW + "x" + imgH);
            }
        }
    }

    private void writeWithRoi(IIOImage image, RoiJ2KImageWriteParam param) throws IOException {
        Object out = getOutput();
        if (!(out instanceof ImageOutputStream)) {
            throw new IllegalStateException("Output has not been set");
        }
        ImageOutputStream stream = (ImageOutputStream) out;
        if (image == null) {
            throw new IllegalArgumentException("image == null");
        }

        boolean writeRaster = image.hasRaster();
        Raster raster = writeRaster ? image.getRaster() : null;
        RenderedImage input = writeRaster ? null : image.getRenderedImage();
        SampleModel sampleModel = writeRaster ? raster.getSampleModel() : input.getSampleModel();

        int imgW = writeRaster ? raster.getWidth() : input.getWidth();
        int imgH = writeRaster ? raster.getHeight() : input.getHeight();
        validateRoiSettings(param, imgW, imgH);

        clearAbortRequest();
        processImageStarted(0);

        J2KImageWriteParamJava j2kwparam = new J2KImageWriteParamJava(image, param);

        if (j2kwparam.getPackPacketHeaderInTile() && j2kwparam.getPackPacketHeaderInMain()) {
            throw new IllegalArgumentException("Packet headers cannot be packed in both tile and main header");
        }
        if (j2kwparam.getLossless() && j2kwparam.getEncodingRate() != Double.MAX_VALUE) {
            throw new IllegalArgumentException("Lossless and an encoding rate cannot be set together");
        }

        if ((!writeRaster && input.getColorModel() instanceof IndexColorModel)
                || (writeRaster && raster.getSampleModel() instanceof MultiPixelPackedSampleModel)) {
            j2kwparam.setDecompositionLevel("0");
            j2kwparam.setLossless(true);
            j2kwparam.setEncodingRate(Double.MAX_VALUE);
            j2kwparam.setQuantizationType("reversible");
            j2kwparam.setFilters(J2KImageWriteParam.FILTER_53);
        } else if (j2kwparam.getEncodingRate() == Double.MAX_VALUE) {
            j2kwparam.setLossless(true);
            j2kwparam.setQuantizationType("reversible");
            j2kwparam.setFilters(J2KImageWriteParam.FILTER_53);
        }

        // ---- the only addition to the standard pipeline ----
        j2kwparam.setROIs(param.toJJ2000Spec());
        j2kwparam.setAlignROI(param.getROIAlignedToCodeBlocks());
        j2kwparam.setStartLevelROI(param.getROIStartLevel());
        // -----------------------------------------------------

        boolean pphTile = j2kwparam.getPackPacketHeaderInTile();
        boolean pphMain = j2kwparam.getPackPacketHeaderInMain();

        int[] bands = param.getSourceBands();
        int ncomp = bands != null ? bands.length : sampleModel.getNumBands();

        RenderedImageSrc imgsrc = writeRaster
                ? new RenderedImageSrc(raster, j2kwparam, this)
                : new RenderedImageSrc(input, j2kwparam, this);

        boolean[] imsigned = new boolean[ncomp];
        for (int i = 0; i < ncomp; i++) {
            imsigned[i] = imgsrc.isOrigSigned(bands != null ? bands[i] : i);
        }

        int refx = j2kwparam.getMinX();
        int refy = j2kwparam.getMinY();
        if (refx < 0 || refy < 0) {
            throw new IIOException("Image origin must not be negative");
        }
        int trefx = j2kwparam.getTileGridXOffset();
        int trefy = j2kwparam.getTileGridYOffset();
        if (trefx < 0 || trefy < 0 || trefx > refx || trefy > refy) {
            throw new IIOException("Invalid tile grid offset");
        }

        Tiler imgtiler = new Tiler(imgsrc, refx, refy, trefx, trefy,
                j2kwparam.getTileWidth(), j2kwparam.getTileHeight());
        ForwCompTransf fctransf = new ForwCompTransf(imgtiler, j2kwparam);
        ImgDataConverter converter = new ImgDataConverter(fctransf);
        ForwardWT dwt = ForwardWT.createInstance(converter, j2kwparam);
        Quantizer quant = Quantizer.createInstance(dwt, j2kwparam);
        ROIScaler rois = ROIScaler.createInstance(quant, j2kwparam);
        EntropyCoder ecoder = EntropyCoder.createInstance(rois, j2kwparam,
                j2kwparam.getCodeBlockSize(), j2kwparam.getPrecinctPartition(),
                j2kwparam.getBypass(), j2kwparam.getResetMQ(), j2kwparam.getTerminateOnByte(),
                j2kwparam.getCausalCXInfo(), j2kwparam.getCodeSegSymbol(),
                j2kwparam.getMethodForMQLengthCalc(), j2kwparam.getMethodForMQTermination());

        File tmpFile = Files.createTempFile("jpeg2000-roi-", ".tmp").toFile();
        FileCodestreamWriter bwriter = null;
        boolean bwriterClosed = false;
        try {
            bwriter = new FileCodestreamWriter(tmpFile, Integer.MAX_VALUE);
            PostCompRateAllocator ralloc = PostCompRateAllocator.createInstance(
                    ecoder, (float) j2kwparam.getEncodingRate(), bwriter, j2kwparam);
            HeaderEncoder headenc = new HeaderEncoder(imgsrc, imsigned, dwt, imgtiler,
                    j2kwparam, rois, ralloc);
            ralloc.setHeaderEncoder(headenc);
            headenc.encodeMainHeader();

            try {
                ralloc.initialize();
            } catch (RuntimeException e) {
                if (WRITE_ABORTED.equals(e.getMessage())) {
                    bwriterClosed = true;
                    bwriter.close();
                    processWriteAborted();
                    return;
                }
                throw e;
            }

            headenc.reset();
            headenc.encodeMainHeader();
            bwriter.commitBitstreamHeader(headenc);
            ralloc.runAndWrite();
            bwriterClosed = true;
            bwriter.close();

            int fileLength = bwriter.getLength();
            int pktspertp = j2kwparam.getPacketPerTilePart();
            if (pktspertp > 0 || pphTile || pphMain) {
                CodestreamManipulator cm = new CodestreamManipulator(tmpFile,
                        imgtiler.getNumTiles(), pktspertp, pphMain, pphTile, false, false);
                fileLength += cm.doCodestreamManipulation();
            }

            // JP2 file format wrapper (or raw codestream if getWriteCodeStreamOnly())
            int nc = imgsrc.getNumComps();
            int[] bpc = new int[nc];
            for (int c = 0; c < nc; c++) {
                bpc[c] = imgsrc.getNomRangeBits(c);
            }

            ColorModel colorModel = input != null ? input.getColorModel() : null;
            if (bands != null && param.getDestinationType() != null) {
                colorModel = param.getDestinationType().getColorModel();
            }
            if (colorModel == null) {
                colorModel = ImageUtil.createColorModel(sampleModel);
            }

            J2KMetadata metadata = null;
            if (!param.getWriteCodeStreamOnly()) {
                J2KMetadata generated = new J2KMetadata(colorModel, sampleModel,
                        imgsrc.getImgWidth(), imgsrc.getImgHeight(), param, this);
                IIOMetadata inMetadata = image.getMetadata();
                if (inMetadata == null) {
                    metadata = generated;
                } else {
                    metadata = (J2KMetadata) convertImageMetadata(inMetadata,
                            new ImageTypeSpecifier(colorModel, sampleModel), param);
                    String fmt = generated.getNativeMetadataFormatName();
                    metadata.mergeTree(fmt, generated.getAsTree(fmt));
                }
            }

            FileFormatWriter ffw = new FileFormatWriter(tmpFile, stream,
                    imgsrc.getImgHeight(), imgsrc.getImgWidth(), nc, bpc, fileLength,
                    colorModel, sampleModel, metadata);
            ffw.writeFileFormat();
        } finally {
            // On failure the codestream writer may still hold the temp file open; close it so the
            // file handle is released and the delete below also works on Windows. close() can only
            // be called once (it appends an end marker), hence the flag.
            if (bwriter != null && !bwriterClosed) {
                try {
                    bwriter.close();
                } catch (IOException ignored) {
                    // already failing; the original exception is the one that matters
                }
            }
            tmpFile.delete();
        }
        processImageComplete();
    }
}
