/**
 * 
 */
package vtc.tools.arupfrequencycalculator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import json.JSONArray;
import json.JSONException;
import json.JSONObject;
import json.JSONTokener;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;
import org.broad.tribble.TribbleException;
import org.broadinstitute.variant.variantcontext.Allele;
import org.broadinstitute.variant.variantcontext.Genotype;
import org.broadinstitute.variant.variantcontext.GenotypesContext;
import org.broadinstitute.variant.variantcontext.VariantContext;

import vtc.datastructures.InvalidInputFileException;
import vtc.datastructures.SupportedFileType;
import vtc.datastructures.VariantPool;
import vtc.tools.setoperator.SetOperator;
import vtc.tools.setoperator.operation.InvalidOperationException;
import vtc.tools.setoperator.operation.Operation;
import vtc.tools.setoperator.operation.OperationFactory;
import vtc.tools.utilitybelt.UtilityBelt;
import vtc.tools.varstats.VariantPoolSummarizer;
import vtc.tools.varstats.VariantRecordSummary;

/**
 * @author markebbert
 *
 */
public class ARUPFrequencyCalculatorEngine {
	
	private static Logger logger = Logger.getLogger(ARUPFrequencyCalculatorEngine.class);
	private static ArgumentParser parser;
	private Namespace parsedArgs;
	private ArrayList<String> analTypes;
	private TreeMap<String, ArrayList<HashMap<String, String>>> sampleManifests
					= new TreeMap<String, ArrayList<HashMap<String, String>>>();
	private HashMap<String, File> masterVCFs = new HashMap<String, File>();
	
	private final static String VCF_KEY = "vcf.file";
	private final static String ANALYSIS_TYPE_KEY = "analysis.type";
	private final static String SAMPLE_NAME_KEY = "sample.name";
	private final static String ANALYSIS_START_KEY = "analysis.start.time";
	private final static String INCLUDE_KEY = "include.in.freq.calc";
	private final static String QC_KEY = "qc.json";

	private final static String CURR_DATE =
			new SimpleDateFormat("yyyyMMMdd").format(Calendar.getInstance().getTime());
	
	private FileWriter logFileWriter;
	
	public ARUPFrequencyCalculatorEngine(String[] args) {
		init(args);
	}
	
	private void init(String[] args){

		parser = ArgumentParsers.newArgumentParser("ARUPFrequencyCalculator");
		parser.description("ARUP Frequency Calculator (AFC) will navigate ARUP result directories " +
				"and calculate test-specific variant frequencies.");
		parser.defaultHelp(true); // Add default values to help menu
		
		parser
			.addArgument("REF")
			.dest("REF")
			.type(String.class)
			.required(true)
			.help("Specify the reference genome location.");

		parser
			.addArgument("DIR")
			.dest("ROOTDIR")
			.type(String.class)
			.required(true)
			.help("Specify the root directory to traverse searching for ARUP result directories.");
		
		parser
			.addArgument("-e", "--excluded_samples-log")
			.dest("EXCLUDED")
			.setDefault("excluded_samples.txt")
			.type(String.class)
			.help("Specify the log file where all excluded sample names are recorded");
		
		parser
			.addArgument("-a", "--analysis-type-file")
			.dest("ANALYSIS_TYPE")
			.setDefault("analysis_types.txt")
			.type(String.class)
			.help("Specify a file with the analysis types to calculate frequencies for. This file should " +
					"be single values delimited by new lines. The values should specify expected values for " +
					"analysis.type in the sampleManifest files.");
		
		try{
			parsedArgs = parser.parseArgs(args);
		} catch (ArgumentParserException e){
			parser.handleError(e);
			System.exit(1);
		}
	}
	
	/**
	 * Calculate frequencies for all samples/variants found in ROOTDIR
	 */
	public void calculateFrequencies(){
		
		// get args
		String rootDir = parsedArgs.getString("ROOTDIR");
		String log = parsedArgs.getString("EXCLUDED");
		String refPath = parsedArgs.getString("REF");
		String analTypeFilePath = parsedArgs.getString("ANALYSIS_TYPE");

		// read analysis type file
		try {
			analTypes = readAnalysisTypeFile(analTypeFilePath);
			logFileWriter = new FileWriter(log);
			
			/* Loop over the directories. If the sample was analyzed with the
			 * appropriate template, then union the corresponding vcf to the
			 * master vcf for that analysis type.
			 */
			findSampleManifests(new File(rootDir), refPath);
			boolean addChr = false;
			unionVCFs(addChr, new File(refPath));
			generateHomoRefCalls(new File(refPath));
			
			/* Calculate frequencies for each analysis type */
            Iterator<String> it = masterVCFs.keySet().iterator();
            String analType;
            ArrayList<VariantRecordSummary> detailedSummary;
            JSONObject analTypeFreqSummary;
            while(it.hasNext()){
                analType = it.next();		
                detailedSummary = calculateFreqs(analType);
                printDetailedSummariesToFile(detailedSummary, analType);
                analTypeFreqSummary = buildNGSWebJSON(detailedSummary, analType);
                postFreqsToNGSWeb(analTypeFreqSummary);
            }

			logFileWriter.close();

		} catch (IOException e) {
            UtilityBelt.printErrorUsageHelpAndExit(parser, logger, e);
		} catch (InvalidInputFileException e) {
            UtilityBelt.printErrorUsageHelpAndExit(parser, logger, e);
		} catch (InvalidOperationException e) {
            UtilityBelt.printErrorUsageHelpAndExit(parser, logger, e);
		} catch (URISyntaxException e) {
            UtilityBelt.printErrorUsageHelpAndExit(parser, logger, e);
		} catch (TribbleException e) {
            UtilityBelt.printErrorUsageAndExit(parser, logger, e);
        } catch (JSONException e) {
            UtilityBelt.printErrorUsageAndExit(parser, logger, e);
		}
		
		// loop over dirs and union vcfs

	}
	
	/**
	 * Read the analysis type file and save analysis types into an ArrayList<String>
	 * @param filePath
	 * @return
	 * @throws IOException
	 */
	private ArrayList<String> readAnalysisTypeFile(String filePath) throws IOException{
		
		logger.info("Analysis Types:");
		BufferedReader br = new BufferedReader(new FileReader(filePath));
		String line, val;
		ArrayList<String> analysisTypes = new ArrayList<String>();
		while((line = br.readLine()) != null){
			// Just take whatever is on the line.
			val = line.replaceAll("\\s", ".");
			analysisTypes.add(val); // replace any white space with "."
			logger.info(val);
		}
		br.close();
		return analysisTypes;
	}
	
	/**
	 * Recursively descend through the directory structure finding sample manifest
	 * files. If the analysis type for that sample was specified, and the sample
	 * is marked to be included, union it to the master.
	 * @param file
	 * @param refPath
	 * @throws IOException
	 */
	private void findSampleManifests(File file, String refPath) throws IOException{
//		logger.info("Descending into: " + file.getAbsolutePath());
		if(!file.exists()){
			logger.info("ERROR: File " + file.getAbsolutePath() + " does not exist!");
			return;
		}
		if(file.isDirectory()){ // Continue to descend through directories
			String[] children = file.list();
			for(String child : children){
				findSampleManifests(new File(file, child), refPath);
			}
		}
		else if(file.isFile()){ // This is a file. If it's a sample manifest, use it.
//			logger.info("File absolute path: " + file.getAbsolutePath());
//			logger.info("File name: " + file.getName());
			if(file.getName().equals("sampleManifest.txt")){
				
				/* Read the manifest into a HashMap */
				HashMap<String, String> manifestMap = readManifest(file);
				
				/* Get the analysis type (i.e., the pipeline template used). Replace whitespace with '.' */
				String analType = manifestMap.get(ANALYSIS_TYPE_KEY).replaceAll("\\s", ".");
				
				/* If a directory for this analysis type does not exist, create it. */
				File analDir = new File(analType);
				if(!analDir.exists()){
					analDir.mkdir();
				}
				
				/* Prepend the parent directory to the VCF path */
				manifestMap.put(VCF_KEY, file.getParent() + "/" + manifestMap.get(VCF_KEY));
				manifestMap.put(QC_KEY, file.getParent() + "/" + manifestMap.get(QC_KEY));
				
				/* Check whether this sample and analType should be included.
				 * If not, log the excluded sample and return.
				 */
				String include = manifestMap.get(INCLUDE_KEY);
				
				/* For now, if the sample doesn't have an include key, assume it's true */
				if(include == null){ include = "true"; }
				if(!analTypes.contains(analType) || !include.toLowerCase().equals("true")){
					String sampleName = manifestMap.get(SAMPLE_NAME_KEY);
					logFileWriter.write(sampleName + "\t" + analType + "\t" + include + "\n");
					return;
				}
				

				/* Collect the manifestMaps by analType */
				ArrayList<HashMap<String, String>> manifestList = sampleManifests.get(analType); 
				
				/* If sampleManifests doesn't already have a list of manifestMaps for
				 * this analType, create one and add it in.
				 */
				if(manifestList == null){
					manifestList = new ArrayList<HashMap<String, String>>();
					manifestList.add(manifestMap);
					sampleManifests.put(analType, manifestList);
				}
				
				/* Otherwise, just add the manifestMap to the existing list */
				else{
					manifestList.add(manifestMap);
				}
			}
		}
		
	}
	
	/**
	 * This method will loop over the sample manifests for each analysis type
	 * and union them into one master vcf for the analysis type.
	 * @param addChr
	 * @param refDict
	 * @throws IOException
	 * @throws InvalidInputFileException
	 * @throws InvalidOperationException
	 * @throws URISyntaxException
	 */
	private void unionVCFs(boolean addChr, File refDict)
			throws IOException, InvalidInputFileException, InvalidOperationException, URISyntaxException{
		HashMap<String, String> manifestMap1, manifestMap2;
		String vcfFile1Args, vcfFile2Args, vcfFile1, vcfFile2;
		TreeMap<String, VariantPool> allVPs;
		String opString = "masterVP=u[vars1:vars2]";
		Operation op;
		boolean forceUniqueNames = false;
		SetOperator so = new SetOperator();
		File master_vcf;
		VariantPool masterVP = null, vp1, vp2;
		ArrayList<String> newSampleNames1, newSampleNames2;
        String sampleName1, sampleName2;

		/* Loop over the analysis types and corresponding sampleManifestMaps
		 * and union the VCFs to a master vcf
		 */
		Iterator<String> analTypeIT = sampleManifests.keySet().iterator();
		String analType;
		ArrayList<HashMap<String, String>> manifestMaps;
		while(analTypeIT.hasNext()){
			analType = analTypeIT.next();
			master_vcf = new File(analType + "/" + CURR_DATE + "--master_vcf--" + analType + ".vcf");
			masterVCFs.put(analType, master_vcf);
			
			/* Loop over the manifestMaps for this analType */
			manifestMaps = sampleManifests.get(analType);
			if(manifestMaps == null){
				throw new RuntimeException("ERROR: should have had sampleManifests for "
							+ analType + " analyses.");
			}
			if(manifestMaps.size() == 1){
				copyFileTo(manifestMaps.get(0).get(VCF_KEY), master_vcf.getAbsolutePath());
				continue;
			}
			for(int i = 0; i < manifestMaps.size(); i++){

				newSampleNames1 = new ArrayList<String>();
				newSampleNames2 = new ArrayList<String>();
				allVPs = new TreeMap<String, VariantPool>();
				
				// If i == 0, union the first two to create the master
				if(i == 0){
					manifestMap1 = manifestMaps.get(i);
					manifestMap2 = manifestMaps.get(++i);
				
					vcfFile1 = manifestMap1.get(VCF_KEY);
					vcfFile2 = manifestMap2.get(VCF_KEY);

					sampleName1 = manifestMap1.get(SAMPLE_NAME_KEY)
							+ ":" + manifestMap1.get(ANALYSIS_START_KEY);
					newSampleNames1.add(sampleName1);

					sampleName2 = manifestMap2.get(SAMPLE_NAME_KEY)
							+ ":" + manifestMap2.get(ANALYSIS_START_KEY);
					newSampleNames2.add(sampleName2);
				
					vcfFile1Args = "vars1=" + vcfFile1;
					vcfFile2Args = "vars2=" + vcfFile2;
					
					vp1 = new VariantPool(vcfFile1Args, false, addChr);
					vp1.changeSampleNames(newSampleNames1);

					vp2 = new VariantPool(vcfFile2Args, false, addChr);
					vp2.changeSampleNames(newSampleNames2);
					
					allVPs.put(vp1.getPoolID(), vp1);
					allVPs.put(vp2.getPoolID(), vp2);
				
					op = OperationFactory.createOperation(opString, allVPs);
					masterVP = so.performUnion(op, UtilityBelt.getAssociatedVariantPoolsAsArrayList(op, allVPs), forceUniqueNames);
				}
				else{
					manifestMap1 = manifestMaps.get(i);
					vcfFile1 = manifestMap1.get(VCF_KEY);
					sampleName1 = manifestMap1.get(SAMPLE_NAME_KEY)
							+ ":" + manifestMap1.get(ANALYSIS_START_KEY);
					newSampleNames1.add(sampleName1);
					vcfFile1Args = "vars1=" + vcfFile1;
					vcfFile2Args = "vars2=" + master_vcf.getAbsolutePath();
					
					vp1 = new VariantPool(vcfFile1Args, false, addChr);
					vp1.changeSampleNames(newSampleNames1);
					vp2 = new VariantPool(vcfFile2Args, false, addChr);
					
					allVPs.put(vp1.getPoolID(), vp1);
					allVPs.put(vp2.getPoolID(), vp2);

					op = OperationFactory.createOperation(opString, allVPs);
					masterVP = so.performUnion(op, UtilityBelt.getAssociatedVariantPoolsAsArrayList(op, allVPs), forceUniqueNames);
					
				}
                logger.info("Printing " + masterVP.getPoolID() + " to file: " + master_vcf.getAbsolutePath());
                VariantPool.printVariantPool(master_vcf.getAbsolutePath(), masterVP, refDict, SupportedFileType.VCF, false);
                logger.info(masterVP.getNumVarRecords() + " variant record(s) written.");
			}
		}
	}
	
	private void generateHomoRefCalls(File refDict) throws FileNotFoundException, JSONException, InvalidInputFileException, URISyntaxException{
		logger.info("Converting No Calls to homozygous reference...");

		long startTime = System.nanoTime();
		

        Iterator<String> analTypeIT = sampleManifests.keySet().iterator();
		String analType, qcFile;
		ArrayList<HashMap<String, String>> manifestMaps;
		NoCallRegionList noCallRegions;
		HashMap<String, NoCallRegionList> noCallRegionsBySample
							= new HashMap<String, NoCallRegionList>();
		HashMap<String, String> manifestMap;
		File master_vcf;
		String sampleName;
		while(analTypeIT.hasNext()){
			analType = analTypeIT.next();
            master_vcf = masterVCFs.get(analType);
			
			/* Loop over the manifestMaps for this analType */
			manifestMaps = sampleManifests.get(analType);
			for(int i = 0; i < manifestMaps.size(); i++){	
				manifestMap = manifestMaps.get(i);
				qcFile = manifestMap.get(QC_KEY);
				sampleName = manifestMap.get(SAMPLE_NAME_KEY)
						+ ":" + manifestMap.get(ANALYSIS_START_KEY);

				noCallRegions = getNoCallRegions(new File(qcFile));
				noCallRegionsBySample.put(sampleName, noCallRegions);
			}
			
			logger.info("Converting for " + analType + " variants");
			generateHomoRefCalls(master_vcf, noCallRegionsBySample, refDict);
		}
		long endTime = System.nanoTime();
		long duration = endTime - startTime;
		long durMins = TimeUnit.NANOSECONDS.toMinutes(duration);
		logger.info("Done in " + durMins + " minutes.");
	}
	
	/**
	 * Convert no call genotypes to homozygous reference for samples that had coverage
	 * at the given location
	 * 
	 * @param master_vcf
	 * @param noCallRegionsBySample
	 * @throws InvalidInputFileException
	 * @throws URISyntaxException 
	 * @throws FileNotFoundException 
	 */
	private void generateHomoRefCalls(File master_vcf, HashMap<String, 
			NoCallRegionList> noCallRegionsBySample, File refDict) throws InvalidInputFileException, FileNotFoundException, URISyntaxException{
		
		// get variant pool
		VariantPool master = new VariantPool(master_vcf.getAbsolutePath(), false, false);
		
		// loop over variants
		Iterator<String> it = master.getVariantIterator();
		String currVarKey;
		VariantContext var, newVar;
		GenotypesContext gc;
		Genotype geno;
		ArrayList<Allele> alleles;
		ArrayList<Genotype> newGenos;
		NoCallRegionList noCallRegionsForSample;
		while(it.hasNext()){
			newGenos = new ArrayList<Genotype>();
			currVarKey = it.next();
			var = master.getVariant(currVarKey);
			gc = var.getGenotypes();
			
			for(int i = 0; i < gc.size(); i++){
				geno = gc.get(i);
				
				/* If the genotype is a nocall, see if it had coverage */
				if(geno.isNoCall()){
					noCallRegionsForSample = noCallRegionsBySample.get(geno.getSampleName());
					if(noCallRegionsForSample == null 
							|| sampleHasCoverageAtLocation(noCallRegionsForSample, var)){
						 alleles = new ArrayList<Allele>();
						 alleles.add(var.getReference());
						 alleles.add(var.getReference());
						 geno = VariantPool.changeAllelesForGenotype(geno, alleles);
					}
				}
				newGenos.add(geno);
			}
			newVar = VariantPool.buildVariant(var, var.getAlleles(), newGenos);
			master.updateVariant(currVarKey, newVar);
		}

        logger.info("Printing " + master.getPoolID() + " to file: " + master_vcf.getAbsolutePath());
        VariantPool.printVariantPool(master_vcf.getAbsolutePath(), master, refDict, SupportedFileType.VCF, false);
        logger.info(master.getNumVarRecords() + " variant record(s) written.");
	}
	
	/**
	 * Loop over the provided nocall regions for the sample and determine whether the
	 * variant overlaps with any. If so, return false. noCallRegions must be in 
	 * 
	 * @param noCallRegions
	 * @param var
	 * @return
	 */
	private boolean sampleHasCoverageAtLocation(NoCallRegionList noCallRegions, VariantContext var){
		
		String chr = var.getChr();
//		int varChr = Integer.parseInt(chr.replaceAll("\\w", ""));
		int varStart = var.getStart();
		int varEnd = var.getEnd();
		
		NoCall varNoCall = new NoCall(null, null, chr, varStart, varEnd, varEnd - varStart);
		
		/* For each no call region, check whether the variant overlaps */
		int comparison;
		NoCall nc;
		while(true){
			nc = noCallRegions.current();
			if(nc == null){
				return true;
			}
		
			comparison = nc.compareTo(varNoCall);
			
			if(comparison < 0){
				noCallRegions.next();
				continue; // Not to the right region yet
			}
			else if(comparison > 0){
				return true; // Passed the region. Must have coverage.
			}
			else{
				return false; // The two overlap. No coverage.
			}
		}
	}
	
	private NoCallRegionList getNoCallRegions(File qcFile) throws FileNotFoundException{
		logger.info("Getting no calls from: " + qcFile.getAbsolutePath());
		final String nocall_key = "nocalls";
		final String regions_key = "regions";
		final String gene_key = "gene";
		final String reason_key = "reason";
		final String chr_key = "chr";
		final String start_key = "start";
		final String end_key = "end";
		final String size_key = "size";
        JSONTokener jt = new JSONTokener(new BufferedReader(new FileReader(qcFile)));
        JSONObject currNoCallJSON;
        NoCall noCall;
        ArrayList<NoCall> noCallRegions = new ArrayList<NoCall>();
        String gene, reason, chr;
        Integer start, stop, size;

        /* If we fail to extract no call information from the json, just return
         * null. We will assume there was coverage and the sample was homo ref.
         */
        try{
            JSONObject noCalls = new JSONObject(jt).getJSONObject(nocall_key);
            JSONArray regions = noCalls.getJSONArray(regions_key);
            for(int j = 0; j < regions.length(); j++){
                currNoCallJSON = regions.getJSONObject(j);
                gene = currNoCallJSON.getString(gene_key);
                reason = currNoCallJSON.getString(reason_key);
                chr = currNoCallJSON.getString(chr_key);
                start = currNoCallJSON.getInt(start_key);
                stop = currNoCallJSON.getInt(end_key);
                size = currNoCallJSON.getInt(size_key);

                noCall = new NoCall(gene, reason, chr, start, stop, size);
                
    //            noCallRegions = noCallRegionsByChr.get(chr);
    //            if(noCallRegions == null){
    //            	noCallRegions = new LinkedList<NoCall>();
    //            	noCallRegionsByChr.put(chr, noCallRegions);
    //            }
                noCallRegions.add(noCall);
            }
        } catch(JSONException e){
        	logger.info("Unable to obtain nocalls from " + qcFile.getAbsolutePath() +
        			". Exception: " + e.getMessage());
        	return null;
        }
        
        /* Sort all of the NoCall lists */
//        Iterator<Integer> it = noCallRegionsByChr.keySet().iterator();
//        while(it.hasNext()){
//        	Collections.sort(noCallRegionsByChr.get(it.next()));
//        }
        Collections.sort(noCallRegions);
        return new NoCallRegionList(noCallRegions);
	}
	
	/**
	 * Calculate the frequencies for the given analType and return
	 * an ArrayList<VariantRecordSummary> giving a detailed summary
	 * of each variant record
	 * 
	 * @param analType
	 * @return
	 * @throws IOException
	 * @throws InvalidInputFileException
	 * @throws InvalidOperationException
	 */
	private ArrayList<VariantRecordSummary> calculateFreqs(String analType)
			throws IOException, InvalidInputFileException, InvalidOperationException{
			
        logger.info(masterVCFs.get(analType));
        String arg = "all_samples=" + masterVCFs.get(analType);
        boolean requireIndex = false, addChr = false;
        VariantPool vp = new VariantPool(arg, requireIndex, addChr);
        ArrayList<VariantRecordSummary> summary =
                VariantPoolSummarizer.summarizeVariantPoolDetailed(vp);
        return summary;
	}
	
	/**
	 * Print a detailed report of the summary to a file based on the master
	 * vcf for the given analysis type
	 * 
	 * @param summary
	 * @param analType
	 * @throws IOException
	 */
	private void printDetailedSummariesToFile(ArrayList<VariantRecordSummary> summary, String analType) throws IOException{
        String master_vcf_file_name = masterVCFs.get(analType).getAbsolutePath();
        String freqs_file_name = master_vcf_file_name.substring(0, master_vcf_file_name.lastIndexOf(".")) + "-freqs.txt";
        
        String header = "Chr\tPos\tID\tRef\tAlt\tRef_allele_count\tAlt_allele_count" +
                "\tRef_sample_count\tAlt_sample_count\tN_samples_with_call\tN_genos_called\tN_total_samples\t" +
                "Alt_genotype_freq\tAlt_sample_freq\tMin_depth\tMax_depth\tAvg_depth\tQuality";
        FileWriter fw = new FileWriter(freqs_file_name);
        fw.write(header);
        for(VariantRecordSummary s : summary){
            fw.write(s.toString() + "\n");
        }
        fw.close();
	}
	
	private JSONObject buildNGSWebJSON(ArrayList<VariantRecordSummary> summary, String analType) throws JSONException{
        JSONObject summaryJSON = new JSONObject();
        JSONArray recordSummaries = new JSONArray();
        for(VariantRecordSummary s : summary){
            recordSummaries.put(s.toJSON());
        }
        summaryJSON.put("dta", analType);
        summaryJSON.put("frequency.list", recordSummaries);
        return summaryJSON;
	}
	
	private boolean postFreqsToNGSWeb(JSONObject varFreqs) throws IOException{
		CloseableHttpClient httpClient = HttpClientBuilder.create().build();

	    try {
	        HttpPost request = new HttpPost("http://ngs-webapp-dev/Variant/UploadVariantFrequencies");
	        StringEntity params =new StringEntity(varFreqs.toString());
	        request.addHeader("content-type", "application/x-www-form-urlencoded");
	        request.addHeader("Accept", "text/plain");
	        request.setEntity(params);
	        HttpResponse response = httpClient.execute(request);
	        
	        logger.info("NGSWeb HTTP Response: " + response.toString());

	        // handle response here...
	    } finally {
	        httpClient.close();
	    }
	    return true;
	}
	
	/**
	 * Given a new vcf, union it to the master for the given analysis type. Or, if
	 * this is the first vcf for the analysis type, make it the master.
	 * @param vcfPath
	 * @param analType
	 * @param sampleName
	 * @param refPath
	 * @throws IOException
	 */
//	private void unionVCFToMaster(String vcfPath, String analType, String sampleName, String refPath) throws IOException{
//		
//		/* If this is the first VCF for this analysis type, just make it the master
//		 * (i.e.) copy it to a new file as the master
//		 */
//		File master_vcf = new File("master_vcf-" + analType + "-" + CURR_DATE + ".vcf");
//		if(!master_vcf.exists()){
//			logger.info("Copying " + vcfPath + " to " + master_vcf.getAbsolutePath());
//			copyFileTo(vcfPath, master_vcf.getName());
//		}
//		else{ // master_vcf exists, so union to it
//			File tmpFile = new File(master_vcf + ".tmp");
//			File tmpFileIdx = new File(master_vcf + ".tmp.idx");
//			String args = "-ti varsA=" + master_vcf + " varsB=" + vcfPath + " -s tmp=u[varsA:varsB] -R "
//					+ refPath + " -o " + tmpFile.getName();
//			logger.info("Running SetOperator with the following args: " + args);
//			SetOperatorEngine soe = new SetOperatorEngine(args.split(" "));
//			soe.operate();
//
//			/* Copy the new tmp file to the master_vcf and then delete */
//			copyFileTo(tmpFile.getName(), master_vcf.getName());
//			tmpFile.delete();
//			tmpFileIdx.delete();
//		}
//	}
	
	/**
	 * Parse an ARUP sample manifest file and return a key -> value map
	 * 
	 * @param manifestFile
	 * @return
	 * @throws IOException
	 */
	private HashMap<String, String> readManifest(File manifestFile) throws IOException{
		logger.info("Reading " + manifestFile.getAbsolutePath());
		BufferedReader br = new BufferedReader(new FileReader(manifestFile));
		String line;
		String[] vals;
		HashMap<String, String> manifestMap = new HashMap<String, String>();
		while((line = br.readLine()) != null){
			vals = line.split("=");
			
			/* Require all lines to have 'key=value' format */
			if(vals.length != 2){
				br.close();
				throw new IOException("ERROR: encountered line without 'key=value' format in " +
						manifestFile.getAbsolutePath() + ": " + line);
			}
			
			manifestMap.put(vals[0], vals[1]);
		}
		br.close();
		return manifestMap;
	}

	/**
	 * Copy on file to a new destination
	 * 
	 * @param srcFile
	 * @param destFile
	 * @throws IOException
	 */
	private void copyFileTo(String srcFile, String destFile) throws IOException{
		FileInputStream src = new FileInputStream(srcFile);
		FileOutputStream dest = new FileOutputStream(destFile);
		dest.getChannel().transferFrom(src.getChannel(), 0, src.getChannel().size());
		src.close();
		dest.close();
	}
}
