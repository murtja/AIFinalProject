import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.commons.lang3.tuple.Pair;
import org.deeplearning4j.models.embeddings.wordvectors.WordVectors;
import org.deeplearning4j.text.tokenization.tokenizer.preprocessor.CommonPreprocessor;
import org.deeplearning4j.text.tokenization.tokenizerfactory.DefaultTokenizerFactory;
import org.deeplearning4j.text.tokenization.tokenizerfactory.TokenizerFactory;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;

import static org.nd4j.linalg.indexing.NDArrayIndex.*;

/*
 * 
 */
public class TweetIterator implements DataSetIterator {
	
	//Define a WHOLE BUNCH of starting variables
	private final int batchSize;
	private final int vectorSize;
	private int cursor = 0;
	private int tweetCount = 0;
	private int currCategory = 0;
	private int tweetPos = 0;
	private final List<String> labels;
	private final List<Pair<String, List<String>>> categoryData = new ArrayList<>();
	private final TokenizerFactory tokenizerFactory;
	private final WordVectors wordVectors;
	private final int truncateLength = 300;
	
	TweetIterator(WordVectors wordVectors, int batchSize, boolean train){
		this.batchSize = batchSize;
		this.labels = new ArrayList<>();
		// Load tweets
		this.loadData(train);
		// Initialize list of labels
		for(Pair<String, List<String>> datum : categoryData){
			this.labels.add(datum.getKey().split(",")[1]);
		}
		this.vectorSize = wordVectors.getWordVector(
				wordVectors.vocab().wordAtIndex(0)).length;
		this.wordVectors = wordVectors;
		
		// Initialize tokenizer factory
		tokenizerFactory = new DefaultTokenizerFactory();
        tokenizerFactory.setTokenPreProcessor(new CommonPreprocessor());
	}
	
	/*
	 * Loads data from files for testing
	 */
	private void loadData(boolean train) {
		String dataDirectory = "LabelledData";
		File categories = new File(dataDirectory + File.separator + "categories.txt");
		
		try(BufferedReader brCategories = new BufferedReader(new FileReader(categories))){
			String temp = "";
			//Load in our train and test data from the files
            while ((temp = brCategories.readLine()) != null) {
                String curFileName = train == true ?
                    dataDirectory + File.separator + "train" + File.separator + temp.split(",")[0] + ".txt" :
                    dataDirectory + File.separator + "test" + File.separator + temp.split(",")[0] + ".txt";
                File currFile = new File(curFileName);
                BufferedReader currBR = new BufferedReader((new FileReader(currFile)));
                String tempCurrLine = "";
                //Read the current line and check if it is empty. If it is not empty, at it
                //to our list of lines.
                List<String> tempList = new ArrayList<>();
                while ((tempCurrLine = currBR.readLine()) != null) {
                	if(!tempCurrLine.isEmpty()){
                		tempList.add(tempCurrLine);
                		tweetCount++;
                	}
                }
                currBR.close();
                //Make a pair for our data and shove it into our category data
                Pair<String, List<String>> tempPair = Pair.of(temp, tempList);
                this.categoryData.add(tempPair);
            }
            brCategories.close();
		} catch (IOException e){
			e.printStackTrace();
		}
	}
	
	/*
	 * Returns the next batch of tweets from the dataset. Bounces back and forth
	 * between our two lists of data, reading one line from each. If one of the
	 * lists is at the end, just skip reading from that list and go back to the 
	 * other list. It takes the number of tweets to return as an parameter, and 
	 * returns the DataSet of tweets based on the parameter.
	 */
	private DataSet nextDataSet(int num) throws IOException {
		List<String> tweets = new ArrayList<>(num);
		int[] category = new int[num];
		
		//Bounce between our two lists adding tweets to the data set. Don't if at end of
		//one list.
		for(int i=0; i< num && cursor < totalExamples(); i++) {
			if(currCategory < categoryData.size()) {
				if(tweetPos < categoryData.get(currCategory).getValue().size()){
					tweets.add(categoryData.get(currCategory).getValue()
							.get(tweetPos));
					//Splits up the category data, used for how we are displaying the two groups
					category[i] = Integer.parseInt(categoryData.get(currCategory)
							.getKey().split(",")[0]);
					cursor++;
				} 
				currCategory++;
			} else {
				currCategory = 0;
				tweetPos++;
				i--;
			}
		}
		
		// Tokenize strings and filter out unknown words so everything doesn't break
		List<List<String>> allTokens = new ArrayList<>(tweets.size());
		int maxLength = 0;
		for(String s : tweets) {
			List<String> tokens = tokenizerFactory.create(s).getTokens();
			List<String> tokensFiltered = new ArrayList<>();
			for(String t : tokens) {
				if(wordVectors.hasWord(t))
					tokensFiltered.add(t);
			}
			//Add the filtered tokens to our total list of tokens.
			allTokens.add(tokensFiltered);
			maxLength = Math.max(maxLength, tokensFiltered.size());
		}

		if(maxLength > truncateLength){
			maxLength = truncateLength;
		}
		
		// Create data for training
		INDArray features = Nd4j.create(tweets.size(), vectorSize, maxLength);
		INDArray labels = Nd4j.create(tweets.size(), this.categoryData.size(), 
				maxLength);
		
		INDArray featuresMask = Nd4j.zeros(tweets.size(), maxLength);
		INDArray labelsMask = Nd4j.zeros(tweets.size(), maxLength);
		
		int[] temp = new int[2];
		for(int i = 0; i < tweets.size(); i++) {
			List<String> tokens = allTokens.get(i);
			temp[0] = i;
			// Get word vectors for each word and put them in the training data
			for (int j = 0; j < tokens.size() && j < maxLength; j++) {
				String token = tokens.get(j);
				INDArray vector = wordVectors.getWordVectorMatrix(token);
				features.put(new INDArrayIndex[] {point(i),  all(), point(j)},
						vector);
				temp[1] = j;
				featuresMask.putScalar(temp, 1.0);
			}
			int idx = category[i];
			int lastIdx = Math.min(tokens.size(), maxLength);
			labels.putScalar(new int[]{i, idx, lastIdx-1}, 1.0);
			labelsMask.putScalar(new int[]{i,  lastIdx-1}, 1.0);
		}
		
		//Define a new dataset based on the features and labels grabbed, then returns it
		DataSet ds = new DataSet(features, labels, featuresMask, labelsMask);
		return ds;
	}

	//Bunch of utility functions for getting and moving different data 
	//and variables around.
	@Override
	public boolean hasNext() {
		return cursor < numExamples();
	}

	@Override
	public DataSet next() {
		return next(batchSize);
	}

	@Override
	public boolean asyncSupported() {
		return true;
	}

	@Override
	public int batch() {
		return batchSize;
	}

	@Override
	public int cursor() {
		return cursor;
	}

	@Override
	public List<String> getLabels() {
		return this.labels;
	}

	@Override
	public DataSetPreProcessor getPreProcessor() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int inputColumns() {
		return vectorSize;
	}

	@Override
	public DataSet next(int num) {
		if (cursor >= this.tweetCount)
			throw new NoSuchElementException();
		try {
			return nextDataSet(num);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public int numExamples() {
		return totalExamples();
	}

	@Override
	public void reset() {
		cursor = 0;
		tweetPos = 0;
		currCategory = 0;

	}

	@Override
	public boolean resetSupported() {
		return true;
	}

	@Override
	public void setPreProcessor(DataSetPreProcessor arg0) {
		throw new UnsupportedOperationException();

	}

	@Override
	public int totalExamples() {
		return this.tweetCount;
	}

	@Override
	public int totalOutcomes() {
		return this.categoryData.size();
	}

}
