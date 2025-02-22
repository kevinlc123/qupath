package qupath.lib.classifiers.opencv.pixel.features;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.annotations.JsonAdapter;

import qupath.lib.classifiers.gui.PixelClassifierStatic;
import qupath.lib.classifiers.opencv.OpenCVDNN;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.images.ImageData;
import qupath.lib.io.OpenCVTypeAdapters;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.processing.OpenCVTools;

@JsonAdapter(OpenCVTypeAdapters.OpenCVTypeAdaptorFactory.class)
public class OpenCVFeatureCalculatorDNN implements OpenCVFeatureCalculator {
	
//	static {
//		FeatureCalculators.FeatureCalculatorTypeAdapterFactory.registerSubtype(OpenCVFeatureCalculatorDNN.class);
//	}
	
	private static Logger logger = LoggerFactory.getLogger(OpenCVFeatureCalculatorDNN.class);

    private OpenCVDNN model;
    
    private PixelClassifierMetadata metadata;
        
    public OpenCVFeatureCalculatorDNN(
    		final OpenCVDNN model, 
    		final PixelClassifierMetadata metadata) {
    	
    	this.model = model;
    	this.metadata = metadata;
    }

    private Mat calculateFeatures(Mat input) throws IOException {
    	
    	Mat blob = opencv_dnn.blobFromImage(input, model.getScale(), null, null, model.doSwapRB(), model.doCrop(), opencv_core.CV_32F);

//        Mat blob = opencv_dnn.blobFromImage(input, scale, input.size(), mean, true, false, opencv_core.CV_32F);
        Mat prob;
        Mat matResult = null;
    	Net net = model.getNet();
        synchronized (net) {
        	net.setInput(blob);
            try {
            	String outputLayerName = model.getOutputLayerName();
            	if (outputLayerName == null)
            		prob = net.forward();
            	else
            		prob = net.forward(outputLayerName);
            } catch (Exception e) {
            	throw new IOException(e);
            }
            
            // TODO: Note that there is a maximum number of supported channels; otherwise we need to extract values some other way
//            FloatIndexer indexer = prob.createIndexer();
//            indexer.release();
            
            MatVector matvec = new MatVector();
            opencv_dnn.imagesFromBlob(prob, matvec);
            if (matvec.size() != 1)
            	throw new IllegalArgumentException("DNN result must be a single image - here, the result is " + matvec.size() + " images");
            matResult = matvec.get(0L);
            
            if (matResult.rows() != input.rows() || matResult.cols() != input.cols())
            	opencv_imgproc.resize(matResult, matResult, input.size());

        }

        return matResult;
    }

    @Override
    public String toString() {
        return model.getName();
    }

	@Override
	public List<Feature<Mat>> calculateFeatures(ImageData<BufferedImage> imageData, RegionRequest request) throws IOException {
		int padding = 0;//getMetadata().getInputPadding(); // TODO: Check necessity of padding
		BufferedImage img = PixelClassifierStatic.getPaddedRequest(imageData.getServer(), request, padding);
		
		Mat mat = OpenCVTools.imageToMat(img);
		
		// Add padding if necessary
		int xPad = 0;
		int yPad = 0;
		if (metadata.strictInputSize()) {
			xPad = metadata.getInputWidth() + padding * 2 - mat.cols();
			yPad = metadata.getInputHeight() + padding * 2 - mat.rows();
		}
		if (xPad > 0 || yPad > 0) {
			opencv_core.copyMakeBorder(mat, mat, 0, yPad, 0, xPad, opencv_core.BORDER_REFLECT);
		}
		
		Mat matResult = calculateFeatures(mat);
		mat.release();
		
        // Remove outer padding if necessary
        if (padding > 0 || xPad > 0 || yPad > 0) {
        	int width = matResult.cols();
        	int height = matResult.rows();
        	try {
        		Mat matTemp = matResult.rowRange(padding, height-padding-yPad).colRange(padding, width-padding-xPad).clone();
//        		Mat matTemp = matResult.apply(new opencv_core.Rect(padding, padding, width-padding*2-xPad, height-padding*2-yPad)).clone();
	        	matResult.release();
	        	matResult = matTemp;
        	} catch (Exception e) {
        		logger.error(
        				String.format("Error cropped Mat %d x %d with rectangle (%d, %d, %d, %d)",
        						width, height, padding, padding, width-padding*2, height-padding*2),
        				e);
        	} finally {
//        		if (rect != null)
//        			rect.close();        		
        	}
        }
        
        MatVector output = new MatVector();
        opencv_core.split(matResult, output);
        List<Feature<Mat>> features = new ArrayList<>();
        for (int i = 0; i < output.size(); i++) {
        	features.add(new DefaultFeature<>(metadata.getChannels().get(i).getName(), output.get(i)));
        }

        return features;
	}
	
	@Override
	public ImmutableDimension getInputSize() {
		return new ImmutableDimension(metadata.getInputWidth(), metadata.getInputHeight());
	}

//	@Override
//	public List<String> getFeatureNames() {
//		return metadata.getChannels().stream().map(c -> c.getName()).collect(Collectors.toList());
//	}

}