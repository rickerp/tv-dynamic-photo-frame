package home.tvphotos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FramingTest {
    private static final float E = 0.001f;

    @Test
    public void coverCentred() {
        // A 2:1 photo in a square box: height fills, width overflows evenly.
        assertArrayEquals(new float[]{-50, 0, 200, 100}, Framing.place(100, 100, 1000, 500, 0.5f, 0.5f, 1), E);
    }

    @Test
    public void panClampsToTheEdges() {
        assertArrayEquals(new float[]{0, 0, 200, 100}, Framing.place(100, 100, 1000, 500, 0f, 0.5f, 1), E);
        assertArrayEquals(new float[]{-100, 0, 200, 100}, Framing.place(100, 100, 1000, 500, 1f, 0.5f, 1), E);
        // Panning past the edge never shows background.
        assertArrayEquals(new float[]{-100, 0, 200, 100}, Framing.place(100, 100, 1000, 500, 5f, 0.5f, 1), E);
    }

    @Test
    public void zoomOutToFitCentres() {
        assertEquals(0.5f, Framing.fitZoom(100, 100, 1000, 500), E);
        assertArrayEquals(new float[]{0, 25, 100, 50}, Framing.place(100, 100, 1000, 500, 0.1f, 0.9f, 0.5f), E);
        // Below fit is held at fit.
        assertArrayEquals(new float[]{0, 25, 100, 50}, Framing.place(100, 100, 1000, 500, 0.5f, 0.5f, 0.1f), E);
    }

    @Test
    public void zoomInIsCappedAtFour() {
        float[] r = Framing.place(100, 100, 100, 100, 0.5f, 0.5f, 9);
        assertEquals(400, r[2], E);
        assertEquals(-150, r[0], E);
    }

    @Test
    public void partlyFitSidesCentreOnlyThatAxis() {
        // Zoomed between fit and cover: the width fits inside (centred), the height still pans.
        float[] r = Framing.place(100, 100, 1000, 500, 0.5f, 0f, 0.8f);
        assertEquals(160, r[2], E);
        assertEquals(80, r[3], E);
        assertEquals(-30, r[0], E);
        assertEquals(10, r[1], E);
    }

    @Test
    public void layouts() {
        assertEquals(2, Framing.cells("pair"));
        assertEquals(3, Framing.cells("row3"));
        assertEquals(3, Framing.cells("big3"));
        assertEquals(4, Framing.cells("grid4"));
        assertEquals(0, Framing.cells("nope"));
        assertNull(Framing.boxes("nope"));
        assertArrayEquals(new float[]{2f / 3, 0, 1f / 3, 0.5f}, Framing.boxes("big3")[1], E);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f, 0.5f}, Framing.boxes("grid4")[3], E);
    }

    @Test
    public void boxesInPixelsLeaveAGap() {
        int[][] px = Framing.pixelBoxes("pair", 1920, 1080);
        assertArrayEquals(new int[]{0, 0, 956, 1080}, px[0]);
        assertArrayEquals(new int[]{964, 0, 956, 1080}, px[1]);
        int[][] grid = Framing.pixelBoxes("grid4", 1920, 1080);
        assertArrayEquals(new int[]{964, 544, 956, 536}, grid[3]);
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, Framing.pixelBoxes(null, 1920, 1080)[0]);
    }
}
