/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.geom;

/**
 * An immutable alternative to Java's AWT Dimension.
 * 
 * @author Pete Bankhead
 *
 */
public class ImmutableDimension {
	
	/**
	 * Width of the ImmutableDimension.
	 */
	final public int width;
	
	/**
	 * Height of the ImmutableDimension.
	 */
	final public int height;
	
	/**
	 * Constructor for a new ImmutableDimension.
	 * @param width
	 * @param height
	 */
	public ImmutableDimension(final int width, final int height) {
		this.width = width;
		this.height = height;
	}
	
	/**
	 * Get the ImmutableDimension width.
	 * @return
	 */
	public int getWidth() {
		return width;
	}
	
	/**
	 * Get the ImmutableDimension height.
	 * @return
	 */
	public int getHeight() {
		return height;
	}
	
}