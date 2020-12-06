/*******************************************************************************
 * Copyright (C) 2020 Ram Sadasiv
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
 ******************************************************************************/
package io.outofprintmagazine.corpus;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.outofprintmagazine.corpus.batch.CorpusBatch;
import io.outofprintmagazine.corpus.batch.model.CorpusBatchStepModel;
import io.outofprintmagazine.util.IParameterStore;
import io.outofprintmagazine.util.ParameterStorePropertiesFile;

public class App
{
	private static final Logger logger = LogManager.getLogger(App.class);
	
	@SuppressWarnings("unused")
	private Logger getLogger() {
		return logger;
	}
		
	public static void main(String[] args) throws Exception {
		CommandLineParser parser = new DefaultParser();
		Options options = new Options();
		options.addOption( "a", "action", true, "REQUIRED. analyze or generate. analyze requires inputPath. generate requires outputPath." );
		options.addOption( "i", "inputPath", true, "Path to input json file (./batch.json)." );
		options.addOption( "o", "outputPath", true, "Path to output json files (.)." );
		options.addOption( "h", "help", false, "print usage" );
		CommandLine cmd = parser.parse( options, args);
		if (cmd.hasOption("help") || cmd.getOptions().length == 0 || !cmd.hasOption("action")) {
			HelpFormatter formatter = new HelpFormatter();
		    formatter.printHelp("oopcorenlp", options);
		    return;
		}
		if (cmd.getOptionValue("action").equals("generate")) {
			App app = new App();
			String outputPath = cmd.hasOption("outputPath")?cmd.getOptionValue("outputPath"):".";
			app.generateTemplates(outputPath);
		    return;
		}
		if (cmd.getOptionValue("action").equals("analyze")) {
			String inputPath = cmd.hasOption("inputPath")?cmd.getOptionValue("inputPath"):".";
			logger.info(inputPath);
    		CorpusBatch batch = CorpusBatch.buildFromFile(inputPath);
    		batch.run();
		    return;
		}
		HelpFormatter formatter = new HelpFormatter();
	    formatter.printHelp("oopcorenlp", options);
 		
	}
	
	private void writeProperties(String outputPath) throws IOException {
		Properties p = new Properties();
		p.load(getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/util/oopcorenlp.properties"));
		String fileCorpus_Path = p.getProperty("fileCorpus_Path", "Corpora");
		p.setProperty("fileCorpus_Path", outputPath + System.getProperty("file.separator", "/") + fileCorpus_Path);
		p.setProperty("wordNet_location", "./data/dict");
		p.setProperty("verbNet_location", "./data/verbnet3.3/");
		FileWriter fout = null;
		try {
			fout = new FileWriter(new File(outputPath + System.getProperty("file.separator", "/") + "oopcorenlp.properties"));
			p.store(fout, "Sample config");
		}
		finally {
			if (fout != null) {
				fout.close();
				fout = null;
			}
		}
	}
	
	private void appendAnalyzeStep(CorpusBatch corpusBatch) throws IOException {
		CorpusBatchStepModel analyzeStep = new CorpusBatchStepModel();
		analyzeStep.setCorpusBatchId(corpusBatch.getData().getCorpusBatchId());
		analyzeStep.setCorpusBatchStepSequenceId(Integer.valueOf(corpusBatch.getData().getCorpusBatchSteps().size()));
		analyzeStep.setCorpusBatchStepId("Analyze");
		analyzeStep.setCorpusBatchStepClass("io.outofprintmagazine.corpus.batch.impl.Analyze");
		ArrayNode customAnnotators = analyzeStep.getProperties().arrayNode();
		List<String> customAnnotatorList = readStreamToList(getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/util/annotators.txt"));
		for (String customAnnotator : customAnnotatorList) {
			customAnnotators.add(customAnnotator);
		}
		analyzeStep.getProperties().set("customAnnotators", customAnnotators);
		corpusBatch.getData().getCorpusBatchSteps().add(analyzeStep);
	}
	
	private void appendAggregateStep(CorpusBatch corpusBatch) throws IOException {
		CorpusBatchStepModel aggregateStep = new CorpusBatchStepModel();
		aggregateStep.setCorpusBatchId(corpusBatch.getData().getCorpusBatchId());
		aggregateStep.setCorpusBatchStepSequenceId(Integer.valueOf(corpusBatch.getData().getCorpusBatchSteps().size()));
		aggregateStep.setCorpusBatchStepId("CorpusAggregate");
		aggregateStep.setCorpusBatchStepClass("io.outofprintmagazine.corpus.batch.impl.CorpusAggregate");
		corpusBatch.getData().getCorpusBatchSteps().add(aggregateStep);

		CorpusBatchStepModel aggregateIdfStep = new CorpusBatchStepModel();
		aggregateIdfStep.setCorpusBatchId(corpusBatch.getData().getCorpusBatchId());
		aggregateIdfStep.setCorpusBatchStepSequenceId(Integer.valueOf(corpusBatch.getData().getCorpusBatchSteps().size()));
		aggregateIdfStep.setCorpusBatchStepId("CoreNLPTfidf");
		aggregateIdfStep.setCorpusBatchStepClass("io.outofprintmagazine.corpus.batch.impl.CoreNLPTfidfScores");
		corpusBatch.getData().getCorpusBatchSteps().add(aggregateIdfStep);		

		CorpusBatchStepModel aggregateZStep = new CorpusBatchStepModel();
		aggregateZStep.setCorpusBatchId(corpusBatch.getData().getCorpusBatchId());
		aggregateZStep.setCorpusBatchStepSequenceId(Integer.valueOf(corpusBatch.getData().getCorpusBatchSteps().size()));
		aggregateZStep.setCorpusBatchStepId("CoreNLPZ");
		aggregateZStep.setCorpusBatchStepClass("io.outofprintmagazine.corpus.batch.impl.CoreNLPZScores");
		corpusBatch.getData().getCorpusBatchSteps().add(aggregateZStep);	

		CorpusBatchStepModel aggregateMBStep = new CorpusBatchStepModel();
		aggregateMBStep.setCorpusBatchId(corpusBatch.getData().getCorpusBatchId());
		aggregateMBStep.setCorpusBatchStepSequenceId(Integer.valueOf(corpusBatch.getData().getCorpusBatchSteps().size()));
		aggregateMBStep.setCorpusBatchStepId("CoreNLPMB");
		aggregateMBStep.setCorpusBatchStepClass("io.outofprintmagazine.corpus.batch.impl.CoreNLPMyersBriggsScores");
		corpusBatch.getData().getCorpusBatchSteps().add(aggregateMBStep);		
		
		CorpusBatchStepModel word2vecStep = new CorpusBatchStepModel();
		word2vecStep.setCorpusBatchId(corpusBatch.getData().getCorpusBatchId());
		word2vecStep.setCorpusBatchStepSequenceId(Integer.valueOf(corpusBatch.getData().getCorpusBatchSteps().size()));
		word2vecStep.setCorpusBatchStepId("Word2Vec");
		word2vecStep.setCorpusBatchStepClass("io.outofprintmagazine.corpus.batch.impl.BuildWord2VecModels");
		corpusBatch.getData().getCorpusBatchSteps().add(word2vecStep);
	}
	
	private void writeChekhovBatch(String outputPath) throws Exception {
		writeFile(
				outputPath, 
				"Chekhov.html", 
				getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/corpus/batch/impl/gutenberg/Chekhov.html")
		);
		writeFile(
				outputPath, 
				"Chekhov.html.properties", 
				getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/corpus/batch/impl/gutenberg/Chekhov.html.properties")
		);
		CorpusBatch chekhovBatch = CorpusBatch.buildFromTemplate("io/outofprintmagazine/corpus/batch/impl/gutenberg/Chekhov.json");
		
		ObjectNode batchProperties = chekhovBatch.getData().getProperties();
		batchProperties.put("propertiesFilePath", outputPath);
		
		CorpusBatchStepModel importDirectoryStep = chekhovBatch.getData().getCorpusBatchSteps().get(0);
		ObjectNode importDirectoryStepProperties = importDirectoryStep.getProperties();
		importDirectoryStepProperties.put("directory", outputPath);
		appendAnalyzeStep(chekhovBatch);
		appendAggregateStep(chekhovBatch);
		new ObjectMapper().writer(new DefaultPrettyPrinter()).writeValue(new File(outputPath + System.getProperty("file.separator", "/") + "ChekhovBatch.json"), chekhovBatch.getData());
		
	}
	
	private void writeMaupassantBatch(String outputPath) throws Exception {
		writeFile(
				outputPath, 
				"Maupassant.html", 
				getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/corpus/batch/impl/gutenberg/Maupassant.html")
		);
		writeFile(
				outputPath, 
				"Maupassant.html.properties", 
				getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/corpus/batch/impl/gutenberg/Maupassant.html.properties")
		);
		CorpusBatch maupassantBatch = CorpusBatch.buildFromTemplate("io/outofprintmagazine/corpus/batch/impl/gutenberg/Maupassant.json");

		ObjectNode batchProperties = maupassantBatch.getData().getProperties();
		batchProperties.put("propertiesFilePath", outputPath);
		
		CorpusBatchStepModel importDirectoryStep = maupassantBatch.getData().getCorpusBatchSteps().get(0);
		ObjectNode importDirectoryStepProperties = importDirectoryStep.getProperties();
		importDirectoryStepProperties.put("directory", outputPath);
		appendAnalyzeStep(maupassantBatch);
		appendAggregateStep(maupassantBatch);		
		new ObjectMapper().writer(new DefaultPrettyPrinter()).writeValue(new File(outputPath + System.getProperty("file.separator", "/") + "MaupassantBatch.json"), maupassantBatch.getData());	
	}
	
	private void writeWodehouseBatch(String outputPath) throws Exception {
		writeFile(
				outputPath, 
				"Wodehouse.txt", 
				getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/corpus/batch/impl/ebook/Wodehouse.txt")
		);
		writeFile(
				outputPath, 
				"Wodehouse.txt.properties", 
				getClass().getClassLoader().getResourceAsStream("io/outofprintmagazine/corpus/batch/impl/ebook/Wodehouse.txt.properties")
		);
		CorpusBatch wodehouseBatch = CorpusBatch.buildFromTemplate("io/outofprintmagazine/corpus/batch/impl/ebook/Wodehouse.json");

		ObjectNode batchProperties = wodehouseBatch.getData().getProperties();
		batchProperties.put("propertiesFilePath", outputPath);
		
		CorpusBatchStepModel importDirectoryStep = wodehouseBatch.getData().getCorpusBatchSteps().get(0);
		ObjectNode importDirectoryStepProperties = importDirectoryStep.getProperties();
		importDirectoryStepProperties.put("directory", outputPath);
		appendAnalyzeStep(wodehouseBatch);
		appendAggregateStep(wodehouseBatch);		
		new ObjectMapper().writer(new DefaultPrettyPrinter()).writeValue(new File(outputPath + System.getProperty("file.separator", "/") + "WodehouseBatch.json"), wodehouseBatch.getData());
	}
	
	private void writeOHenryBatch(String outputPath) throws Exception {
		CorpusBatch ohenryBatch = CorpusBatch.buildFromTemplate("io/outofprintmagazine/corpus/batch/impl/wikisource/OHenry.json");
		ObjectNode batchProperties = ohenryBatch.getData().getProperties();
		batchProperties.put("propertiesFilePath", outputPath);
		
		CorpusBatchStepModel importDirectoryStep = ohenryBatch.getData().getCorpusBatchSteps().get(0);
		ObjectNode importDirectoryStepProperties = importDirectoryStep.getProperties();
		importDirectoryStepProperties.put("directory", outputPath);
		appendAnalyzeStep(ohenryBatch);
		appendAggregateStep(ohenryBatch);		
		new ObjectMapper().writer(new DefaultPrettyPrinter()).writeValue(new File(outputPath + System.getProperty("file.separator", "/") + "OHenryBatch.json"), ohenryBatch.getData());
	}
	
	public void generateTemplates(String outputPath) throws Exception {
		writeProperties(outputPath);
		writeChekhovBatch(outputPath);
		writeMaupassantBatch(outputPath);
		writeWodehouseBatch(outputPath);
		writeOHenryBatch(outputPath);
	}
	
	protected IParameterStore loadParameterStore(String path, String fileName) throws IOException {
		return new ParameterStorePropertiesFile(path, fileName);
	}
	
	protected Properties loadProperties(String path, String fileName) throws IOException {
    	InputStream input = null;
    	Properties props = new Properties();
    	try {
    		input = new FileInputStream(String.format("%s/%s", path, fileName));
    		props.load(input);
    		return props;
    	}
    	finally {
    		if (input != null) {
    			input.close();
    		}
    		input = null;
    	}
	}
	
	@SuppressWarnings("unused")
	private void writeFile(String path, String fileName, String content) throws IOException {
		FileOutputStream fout = null;
		try {
			File f = new File(path + System.getProperty("file.separator", "/") + fileName);
			if (!f.getParentFile().exists()) {
				f.getParentFile().mkdirs();
			}
			fout = new FileOutputStream(f);
			fout.write(content.getBytes());
		}
		finally {
			if (fout != null) {
				fout.close();
				fout.flush();
				fout = null;
			}
		}
	}
	
	@SuppressWarnings("unused")
	private void writeFile(String path, String fileName, JsonNode content) throws IOException {
		File f = new File(path + System.getProperty("file.separator", "/") + fileName);
		ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		mapper.configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
		mapper.writeValue(f, content);
	}
	
	private void writeFile(String path, String fileName, InputStream in) throws IOException {
		File f = new File(path + System.getProperty("file.separator", "/") + fileName);
		FileOutputStream fout = null;
		try {
			fout = new FileOutputStream(f);
			IOUtils.copy(in,fout);
		}
		finally {
			in.close();
			if (fout != null) {
				fout.flush();
				fout.close();
			}
		}
	}
	
	@SuppressWarnings("unused")
	private String readFile(String path, String fileName) throws IOException {
		File f = new File(path + System.getProperty("file.separator", "/") + fileName);
		FileInputStream fin = null;
		try {
			fin = new FileInputStream(f);
			return IOUtils.toString(
	    		fin,
	    		StandardCharsets.UTF_8.name()
			);
		}
		finally {
			if (fin != null) {
				fin.close();
				fin = null;
			}
		}		
	}
	
	@SuppressWarnings("unused")
	private List<String> readFileToList(String path, String fileName) throws IOException {
		List<String> allLines = FileUtils.readLines(
					new File(
							path + System.getProperty("file.separator", "/") + fileName
					),
					StandardCharsets.UTF_8.name()
		);
		List<String> retval = new ArrayList<String>();
		for (String line : allLines) {
			if (!line.startsWith("#") && !line.startsWith("/")) {
				retval.add(line);
			}
		}
		return retval;
	}
	
	private List<String> readStreamToList(InputStream in) throws IOException {
		try {
			List<String> allLines = IOUtils.readLines(
					in,
					StandardCharsets.UTF_8.name()
			);
			List<String> retval = new ArrayList<String>();
			for (String line : allLines) {
				if (!line.startsWith("#") && !line.startsWith("/")) {
					retval.add(line);
				}
			}
			return retval;
		}
		finally {
			in.close();
		}
	}
}
