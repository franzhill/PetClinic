package com.fhi.pet_clinic.utils.colour;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The Colour Reader provides image colour composition analysis functionality.
 * <p>
 * Build a Colour Reader with the required parameters, then execute one of the
 * analysis methods, passing it the BufferedImage instance to analyse.
 */
public class ColourReader {

	/**
	 * Create a new Colour Reader with default parameters.
	 */
	private ColourReader() {}

	/**
     * Determine a single average colour of an image, across the entire image.
     *
     * @param image image to analyse
     * @return average colour of the image
     */
	public static HSBColour averageColour(BufferedImage image) {
		final List<Integer> samples = readImage(image, 1);

		/*
		   loop through each sample, building up cumulative r/g/b totals then
		   divide the cumulative totals by number of samples to get an average
		   colour.
		*/

		final int[] totalRGB = new int[] { 0, 0, 0 };

		samples.forEach(rgb -> {
			totalRGB[0] += (rgb >> 16) & 0xFF;
			totalRGB[1] += (rgb >> 8) & 0xFF;
			totalRGB[2] += (rgb) & 0xFF;
		});

		return new HSBColour(Color.RGBtoHSB(totalRGB[0] / samples.size(),
											totalRGB[1] / samples.size(),
											totalRGB[2] / samples.size(), null));
	}

	private static List<Integer> readImage(BufferedImage image, float resolution) {
		final int xStep = image.getWidth() / (int)(image.getWidth() * resolution);
		final int yStep = image.getHeight() / (int)(image.getHeight() * resolution);

		final List<Integer> result = new ArrayList<>();
		for (int x = 0; x < image.getWidth(); x += xStep) {
			for (int y = 0; y < image.getHeight(); y += yStep) {
				result.add(image.getRGB(x, y));
			}
		}

		return result;
	}
}