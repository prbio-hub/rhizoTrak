/* 
 * This file is part of the rhizoTrak project.
 * 
 * Note that rhizoTrak extends TrakEM2, hence, its code base substantially 
 * relies on the source code of the TrakEM2 project and the corresponding Fiji 
 * plugin, initiated by A. Cardona in 2005. Large portions of rhizoTrak's code 
 * are directly derived/copied from the source code of TrakEM2.
 * 
 * For more information on TrakEM2 please visit its websites:
 * 
 *  https://imagej.net/TrakEM2
 * 
 *  https://github.com/trakem2/TrakEM2/wiki
 * 
 * Fore more information on rhizoTrak, visit
 *
 *  https://github.com/prbio-hub/rhizoTrak/wiki
 *
 * Both projects, TrakEM2 and rhizoTrak, are released under GPL. 
 * Please find below first the copyright notice of rhizoTrak, and further on
 * (in case that this file was part of the original TrakEM2 source code base
 * and contained a TrakEM2 file header) the original file header with the 
 * TrakEM2 license note.
 */

/*
 * Copyright (C) 2018 - @YEAR@ by the rhizoTrak development team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Fore more information on rhizoTrak, visit
 *
 *    https://github.com/prbio-hub/rhizoTrak/wiki
 *
 */

/* === original file header below (if any) === */

/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package legacy.org.janelia.intensity;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.imageio.ImageIO;

import de.unihalle.informatik.rhizoTrak.Project;
import de.unihalle.informatik.rhizoTrak.display.Layer;
import de.unihalle.informatik.rhizoTrak.display.Patch;
import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TransformMesh;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.trakem2.util.Downsampler;

/**
 * Render a patch.
 *
 * @author Stephan Saalfeld saalfelds@janelia.hhmi.org
 */
public class Render
{
	private Render() {}

	/**
	 * Create a {@link BufferedImage} from an existing pixel array.  Make sure
	 * that pixels.length == width * height.
	 *
	 * @param pixels
	 * @param width
	 * @param height
	 *
	 * @return BufferedImage
	 */
	final static public BufferedImage createARGBImage( final int[] pixels, final int width, final int height )
	{
		assert( pixels.length == width * height ) : "The number of pixels is not equal to width * height.";

		final BufferedImage image = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
		final WritableRaster raster = image.getRaster();
		raster.setDataElements( 0, 0, width, height, pixels );
		return image;
	}


	final static void saveImage( final BufferedImage image, final String path, final String format ) throws IOException
	{
		ImageIO.write( image, format, new File( path ) );
	}

	/**
	 * Sample the average scaling of a given {@link CoordinateTransform} by transferring
	 * a set of point samples using the {@link CoordinateTransform} and then
	 * least-squares fitting a {@link SimilarityModel2D} to it.
	 *
	 * @param ct
	 * @param width of the samples set
	 * @param height of the samples set
	 * @param dx spacing between samples
	 *
	 * @return average scale factor
	 */
	final static protected  double sampleAverageScale( final CoordinateTransform ct, final int width, final int height, final double dx )
	{
		final ArrayList< PointMatch > samples = new ArrayList< PointMatch >();
		for ( double y = 0; y < height; y += dx )
		{
			for ( double x = 0; x < width; x += dx )
			{
				final Point p = new Point( new double[]{ x, y } );
				p.apply( ct );
				samples.add( new PointMatch( p, p ) );
			}
		}
		final SimilarityModel2D model = new SimilarityModel2D();
		try
		{
			model.fit( samples );
		}
		catch ( final NotEnoughDataPointsException e )
		{
			e.printStackTrace( System.err );
			return 1;
		}
		final double[] data = new double[ 6 ];
		model.toArray( data );
		return Math.sqrt( data[ 0 ] * data[ 0 ] + data[ 1 ] * data[ 1 ] );
	}


	final static protected int bestMipmapLevel( final double scale )
	{
		int invScale = ( int )( 1.0 / scale );
		int scaleLevel = 0;
		while ( invScale > 1 )
		{
			invScale >>= 1;
			++scaleLevel;
		}
		return scaleLevel;
	}

	/**
	 * Create an affine transformation that compensates for both scale and
	 * pixel shift of a mipmap level that was generated by top-left pixel
	 * averaging.
	 *
	 * @param scaleLevel
	 * @return
	 */
	final static protected AffineModel2D createScaleLevelTransform( final int scaleLevel )
	{
		final AffineModel2D a = new AffineModel2D();
		final int scale = 1 << scaleLevel;
		final double t = ( scale - 1 ) * 0.5;
		a.set( scale, 0, 0, scale, t, t );
		return a;
	}

	/**
	 * Renders a patch, mapping its intensities [min, max] &rarr; [0, 1]
	 *
	 * @param patch the patch to be rendered
	 * @param targetImage target pixels, specifies the target box
	 * @param targetWeight target weight pixels, depending on alpha
	 * @param x target box offset in world coordinates
	 * @param y target box offset in world coordinates
	 * @param scale target scale
	 */
	final static public void render(
			final Patch patch,
			final int coefficientsWidth,
			final int coefficientsHeight,
			final FloatProcessor targetImage,
			final FloatProcessor targetWeight,
			final ColorProcessor targetCoefficients,
			final double x,
			final double y,
			final double scale )
	{
		/* assemble coordinate transformations and add bounding box offset */
		final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
		ctl.add( patch.getFullCoordinateTransform() );
		final AffineModel2D affineScale = new AffineModel2D();
		affineScale.set( scale, 0, 0, scale, -x * scale, -y * scale );
		ctl.add( affineScale );

		/* estimate average scale and generate downsampled source */
		final int width = patch.getOWidth(), height = patch.getOHeight();
		final double s = sampleAverageScale( ctl, width, height, width / patch.getMeshResolution() );
		final int mipmapLevel = bestMipmapLevel( s );
		final ImageProcessor ipMipmap = Downsampler.downsampleImageProcessor( patch.getImageProcessor(), mipmapLevel );

		/* create a target */
		final ImageProcessor tp = ipMipmap.createProcessor( targetImage.getWidth(), targetImage.getHeight() );

		/* prepare and downsample alpha mask if there is one */
		final ByteProcessor bpMaskMipmap;
		final ByteProcessor bpMaskTarget;

		final ByteProcessor bpMask = patch.getAlphaMask();

		if ( bpMask == null )
		{
			bpMaskMipmap = null;
			bpMaskTarget = null;
		}
		else
		{
			bpMaskMipmap  = bpMask == null ? null : Downsampler.downsampleByteProcessor( bpMask, mipmapLevel );
			bpMaskTarget = new ByteProcessor( tp.getWidth(), tp.getHeight() );
		}

		/* create coefficients map */
		final ColorProcessor cp = new ColorProcessor( ipMipmap.getWidth(), ipMipmap.getHeight() );
		final int w = cp.getWidth();
		final int h = cp.getHeight();
		for ( int yi = 0; yi < h; ++yi )
		{
			final int yc = yi * coefficientsHeight / h;
			final int ic = yc * coefficientsWidth;
			final int iyi = yi * w;
			for ( int xi = 0; xi < w; ++xi )
				cp.set( iyi + xi, ic + ( xi * coefficientsWidth / w ) + 1 );
		}

		/* attach mipmap transformation */
		final CoordinateTransformList< CoordinateTransform > ctlMipmap = new CoordinateTransformList< CoordinateTransform >();
		ctlMipmap.add( createScaleLevelTransform( mipmapLevel ) );
		ctlMipmap.add( ctl );

		/* create mesh */
		final CoordinateTransformMesh mesh = new CoordinateTransformMesh( ctlMipmap,  patch.getMeshResolution(), ipMipmap.getWidth(), ipMipmap.getHeight() );

		/* render */
		final ImageProcessorWithMasks source = new ImageProcessorWithMasks( ipMipmap, bpMaskMipmap, null );
		final ImageProcessorWithMasks target = new ImageProcessorWithMasks( tp, bpMaskTarget, null );
		final TransformMeshMappingWithMasks< TransformMesh > mapping = new TransformMeshMappingWithMasks< TransformMesh >( mesh );
		mapping.mapInterpolated( source, target, 1 );

		final TransformMeshMapping< TransformMesh > coefficientsMapMapping = new TransformMeshMapping< TransformMesh >( mesh );
		coefficientsMapMapping.map( cp, targetCoefficients, 1 );

		/* set alpha channel */
		final byte[] alphaPixels;

		if ( bpMaskTarget != null )
			alphaPixels = ( byte[] )bpMaskTarget.getPixels();
		else
			alphaPixels = ( byte[] )target.outside.getPixels();

		/* convert */
		final double min = patch.getMin();
		final double max = patch.getMax();
		final double a = 1.0 / ( max - min );
		final double b = 1.0 / 255.0;

		for ( int i = 0; i < alphaPixels.length; ++i )
			targetImage.setf( i, ( float )( ( tp.getf( i ) - min ) * a ) );

		for ( int i = 0; i < alphaPixels.length; ++i )
			targetWeight.setf( i, ( float )( ( alphaPixels[ i ] & 0xff ) * b ) );
	}

	final static public void main( final String... args )
	{
		new ImageJ();

		final double scale = 0.05;
//		final double scale = 1;
		final int numCoefficients = 4;

		final Project project = Project.openFSProject( "/home/saalfeld/tmp/bock-lens-correction/subproject.xml", false );
		final Layer layer = project.getRootLayerSet().getLayer( 0 );
		final ArrayList< Patch > patches = ( ArrayList )layer.getDisplayables( Patch.class );

		final Patch patch1 = patches.get( 0 );
		final Patch patch2 = patches.get( 2 );
		final Rectangle box = patch1.getBoundingBox().intersection( patch2.getBoundingBox() );
		//final Rectangle box = patch1.getBoundingBox();

		final int w = ( int )( box.width * scale + 0.5 );
		final int h = ( int )( box.height * scale + 0.5 );

		final FloatProcessor pixels1 = new FloatProcessor( w, h );
		final FloatProcessor weights1 = new FloatProcessor( w, h );
		final ColorProcessor coefficients1 = new ColorProcessor( w, h );
		final FloatProcessor pixels2 = new FloatProcessor( w, h );
		final FloatProcessor weights2 = new FloatProcessor( w, h );
		final ColorProcessor coefficients2 = new ColorProcessor( w, h );

		render( patch1, numCoefficients, numCoefficients, pixels1, weights1, coefficients1, box.x, box.y, scale );
		render( patch2, numCoefficients, numCoefficients, pixels2, weights2, coefficients2, box.x, box.y, scale );

		final ImageStack stack = new ImageStack( w, h );
		stack.addSlice( pixels1 );
		stack.addSlice( pixels2 );
		stack.addSlice( weights1 );
		stack.addSlice( weights2 );
		stack.addSlice( coefficients1.convertToFloatProcessor() );
		stack.addSlice( coefficients2.convertToFloatProcessor() );

		new ImagePlus( "", stack ).show();
	}
}
