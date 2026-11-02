package io.github._4321pranay.jpeg2000.roi;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@link J2KImageWriteParam} with rectangular region-of-interest (ROI) support.
 *
 * <p>ROI uses the JPEG 2000 Part 1 "max-shift" method: wavelet coefficients inside the regions are
 * scaled above every background bit-plane, so when an encoding rate is set the regions are coded first
 * and at higher quality than the rest of the image. Every compliant JPEG 2000 decoder can read the
 * result; the region positions are not stored in the file.
 *
 * <p>All regions apply to all image components. Every other setting of {@link J2KImageWriteParam}
 * (encoding rate, filter, code-block size, progression, ...) works as usual.
 */
public class RoiJ2KImageWriteParam extends J2KImageWriteParam {

    private final List<Rectangle> rois = new ArrayList<>();
    private boolean alignROI = false;
    private int startLevelROI = -1;

    public RoiJ2KImageWriteParam() {
        super();
    }

    public RoiJ2KImageWriteParam(Locale locale) {
        super(locale);
    }

    /**
     * Adds a rectangular region of interest, in image pixel coordinates.
     *
     * @throws IllegalArgumentException if {@code roi} is null, empty or has a negative origin
     */
    public void addROI(Rectangle roi) {
        if (roi == null || roi.isEmpty()) {
            throw new IllegalArgumentException("ROI must be a non-empty rectangle");
        }
        if (roi.x < 0 || roi.y < 0) {
            throw new IllegalArgumentException("ROI origin must not be negative: " + roi);
        }
        rois.add(new Rectangle(roi));
    }

    /** Replaces all regions of interest. {@code null} or an empty list disables ROI coding. */
    public void setROIs(List<Rectangle> roiList) {
        List<Rectangle> validated = new ArrayList<>();
        if (roiList != null) {
            for (Rectangle r : roiList) {
                if (r == null || r.isEmpty() || r.x < 0 || r.y < 0) {
                    throw new IllegalArgumentException("Invalid ROI: " + r);
                }
                validated.add(new Rectangle(r));
            }
        }
        rois.clear();
        rois.addAll(validated);
    }

    /** Returns copies of the regions of interest; empty if none. */
    public List<Rectangle> getROIs() {
        List<Rectangle> copy = new ArrayList<>(rois.size());
        for (Rectangle r : rois) {
            copy.add(new Rectangle(r));
        }
        return Collections.unmodifiableList(copy);
    }

    /** True when at least one region of interest is set. */
    public boolean hasROIs() {
        return !rois.isEmpty();
    }

    /**
     * If true, the ROI mask covers whole code-blocks only: faster to encode, but the protected area
     * is coarser than the given rectangles. Default {@code false}.
     */
    public void setROIAlignedToCodeBlocks(boolean align) {
        this.alignROI = align;
    }

    public boolean getROIAlignedToCodeBlocks() {
        return alignROI;
    }

    /**
     * Resolution level from which ROI coding starts: 0 treats the lowest resolution level entirely as
     * ROI, 1 the two lowest, and so on. Default -1 applies ROI on all levels.
     *
     * @throws IllegalArgumentException if {@code level < -1}
     */
    public void setROIStartLevel(int level) {
        if (level < -1) {
            throw new IllegalArgumentException("ROI start level must be >= -1, was " + level);
        }
        this.startLevelROI = level;
    }

    public int getROIStartLevel() {
        return startLevelROI;
    }

    /** JJ2000 "-Rroi" syntax: {@code R <left> <top> <width> <height>} per region. */
    String toJJ2000Spec() {
        StringBuilder sb = new StringBuilder();
        for (Rectangle r : rois) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("R ").append(r.x).append(' ').append(r.y).append(' ')
              .append(r.width).append(' ').append(r.height);
        }
        return sb.toString();
    }
}
