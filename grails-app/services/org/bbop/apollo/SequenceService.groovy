package org.bbop.apollo

import org.bbop.apollo.gwt.shared.FeatureStringEnum

import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils
import org.bbop.apollo.sequence.SequenceTranslationHandler
import org.bbop.apollo.sequence.StandardTranslationTable
import org.bbop.apollo.sequence.Strand
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONException
import org.codehaus.groovy.grails.web.json.JSONObject
import htsjdk.samtools.reference.IndexedFastaSequenceFile
import java.util.zip.CRC32

@Transactional
class SequenceService {
    
    def configWrapperService
    def grailsApplication
    def featureService
    def transcriptService
    def exonService
    def cdsService
    def gff3HandlerService

    List<FeatureLocation> getFeatureLocations(Sequence sequence){
        FeatureLocation.findAllBySequence(sequence)
    }

    /**
     * Get residues from sequence . . . could be multiple locations
     * @param feature
     * @return
     */
    String getResiduesFromFeature(Feature feature ) {
        List<FeatureLocation> featureLocationList = FeatureLocation.createCriteria().list {
            eq("feature",feature)
            order("fmin","asc")
        }
        String returnResidue = ""
        Sequence s=featureLocationList.first().sequence
        IndexedFastaSequenceFile file=new IndexedFastaSequenceFile(new File(s.organism.fasta))

        for(FeatureLocation featureLocation in featureLocationList){
            log.debug "Iterating feature location ${featureLocation}"
            returnResidue += getResidueFromFeatureLocation(featureLocation, file)
        }
        
        if(featureLocationList.first().strand==Strand.NEGATIVE.value){
            returnResidue = SequenceTranslationHandler.reverseComplementSequence(returnResidue)
        }

        return returnResidue
    }

    String getResidueFromFeatureLocation(FeatureLocation featureLocation, IndexedFastaSequenceFile file) {
        return getResiduesFromSequence(featureLocation.sequence,featureLocation.fmin,featureLocation.fmax, file)
    }

    String getResiduesFromSequence(Sequence sequence, int fmin, int fmax, IndexedFastaSequenceFile file) {
        log.debug "${sequence} ${fmin} ${fmax} ${sequence.name}"
        def nucs=file.getSubsequenceAt(sequence.name,fmin,fmax)
        def bases=new String(nucs.getBases())
        log.debug bases
        return bases
    }


    private static String generatorSampleDNA(int size){
        return RandomStringUtils.random(size,['A','T','C','G'] as char[])
    }
    

    private JSONArray convertJBrowseJSON(InputStream inputStream) throws IOException, JSONException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder buffer = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            buffer.append(line);
        }
        return new JSONArray(buffer.toString());
    }

    def loadRefSeqs(Organism organism ) {
        log.info "loading refseq ${organism.refseqFile}"
        organism.valid = false ;
        organism.save(flush: true, failOnError: true,insert:false)

        File refSeqsFile = new File(organism.refseqFile);
        log.info "file exists ${refSeqsFile.exists()}"
        BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(refSeqsFile));
        JSONArray refSeqs = convertJBrowseJSON(bufferedInputStream);
        log.debug "refseq length ${refSeqs.size()}"


        // delete all sequence for the organism
        Sequence.deleteAll(Sequence.findAllByOrganism(organism))
        for (int i = 0; i < refSeqs.length(); ++i) {
            JSONObject refSeq = refSeqs.getJSONObject(i);
            String name = refSeq.getString("name");
            int length = refSeq.getInt("length");
            int start = refSeq.getInt("start");
            int end = refSeq.getInt("end");
            Sequence sequence = new Sequence(
                    organism: organism
                    , length: length
                    , start: start
                    , end: end
                    , name: name
            ).save(failOnError: true)
        }


        organism.valid = true
        organism.save(flush: true,insert:false,failOnError: true)

        bufferedInputStream.close();
    }

    def setResiduesForFeature(SequenceAlteration sequenceAlteration, String residue) {
        sequenceAlteration.alterationResidue = residue
    }

    def setResiduesForFeatureFromLocation(Deletion deletion) {
        FeatureLocation featureLocation = deletion.featureLocation
        deletion.alterationResidue = getResidueFromFeatureLocation(featureLocation)
    }
    
    
    def getSequenceForFeature(Feature gbolFeature, String type, int flank = 0) {
        // Method returns the sequence for a single feature
        // Directly called for FASTA Export
        String sequence = null
        StandardTranslationTable standardTranslationTable = new StandardTranslationTable()
        
        if (type.equals(FeatureStringEnum.TYPE_PEPTIDE.value)) {
            if (gbolFeature instanceof Transcript && transcriptService.isProteinCoding((Transcript) gbolFeature)) {
                CDS cds = transcriptService.getCDS((Transcript) gbolFeature)
                String rawSequence = featureService.getResiduesWithAlterationsAndFrameshifts(cds)
                sequence = SequenceTranslationHandler.translateSequence(rawSequence, standardTranslationTable, true, cdsService.getStopCodonReadThrough(cds) != null)
                if (sequence.charAt(sequence.size() - 1) == StandardTranslationTable.STOP.charAt(0)) {
                    sequence = sequence.substring(0, sequence.size() - 1)
                }
                int idx;
                if ((idx = sequence.indexOf(StandardTranslationTable.STOP)) != -1) {
                    String codon = rawSequence.substring(idx * 3, idx * 3 + 3)
                    String aa = configWrapperService.getTranslationTable().getAlternateTranslationTable().get(codon)
                    if (aa != null) {
                        sequence = sequence.replace(StandardTranslationTable.STOP, aa)
                    }
                }
            } else if (gbolFeature instanceof Exon && transcriptService.isProteinCoding(exonService.getTranscript((Exon) gbolFeature))) {
                log.debug "Fetching peptide sequence for selected exon: ${gbolFeature}"
                String rawSequence = exonService.getCodingSequenceInPhase((Exon) gbolFeature, true)
                sequence = SequenceTranslationHandler.translateSequence(rawSequence, standardTranslationTable, true, cdsService.getStopCodonReadThrough(transcriptService.getCDS(exonService.getTranscript((Exon) gbolFeature))) != null)
                if (sequence.length()>0 && sequence.charAt(sequence.length() - 1) == StandardTranslationTable.STOP.charAt(0)) {
                    sequence = sequence.substring(0, sequence.length() - 1)
                }
                int idx
                if ((idx = sequence.indexOf(StandardTranslationTable.STOP)) != -1) {
                    String codon = rawSequence.substring(idx * 3, idx * 3 + 3)
                    String aa = configWrapperService.getTranslationTable().getAlternateTranslationTable().get(codon)
                    if (aa != null) {
                        sequence = sequence.replace(StandardTranslationTable.STOP, aa)
                    }
                }
            } else {
                sequence = ""
            }
        } else if (type.equals(FeatureStringEnum.TYPE_CDS.value)) {
            if (gbolFeature instanceof Transcript && transcriptService.isProteinCoding((Transcript) gbolFeature)) {
                sequence = featureService.getResiduesWithAlterationsAndFrameshifts(transcriptService.getCDS((Transcript) gbolFeature))
            } else if (gbolFeature instanceof Exon && transcriptService.isProteinCoding(exonService.getTranscript((Exon) gbolFeature))) {
                log.debug "Fetching CDS sequence for selected exon: ${gbolFeature}"
                sequence = exonService.getCodingSequenceInPhase((Exon) gbolFeature, false)
            } else {
                sequence = ""
            }

        } else if (type.equals(FeatureStringEnum.TYPE_CDNA.value)) {
            if (gbolFeature instanceof Transcript || gbolFeature instanceof Exon) {
                sequence = featureService.getResiduesWithAlterationsAndFrameshifts(gbolFeature)
            } else {
                sequence = ""
            }
        } else if (type.equals(FeatureStringEnum.TYPE_GENOMIC.value)) {

            int fmin = gbolFeature.getFmin() - flank
            int fmax = gbolFeature.getFmax() + flank

            if (flank > 0) {
                if (fmin < 0) {
                    fmin = 0
                }
                if (fmin < gbolFeature.getFeatureLocation().sequence.start) {
                    fmin = gbolFeature.getFeatureLocation().sequence.start
                }
                if (fmax > gbolFeature.getFeatureLocation().sequence.length) {
                    fmax = gbolFeature.getFeatureLocation().sequence.length
                }
                if (fmax > gbolFeature.getFeatureLocation().sequence.end) {
                    fmax = gbolFeature.getFeatureLocation().sequence.end
                }

            }
            FlankingRegion genomicRegion = new FlankingRegion(
                    name: gbolFeature.name
                    , uniqueName: gbolFeature.uniqueName + "_flank"
            ).save()
            FeatureLocation genomicRegionLocation = new FeatureLocation(
                    feature: genomicRegion
                    , fmin: fmin
                    , fmax: fmax
                    , strand: gbolFeature.strand
                    , sequence: gbolFeature.getFeatureLocation().sequence
            ).save()
            genomicRegion.addToFeatureLocations(genomicRegionLocation)
            // since we are saving the genomicFeature object, the backend database will have these entities
            gbolFeature = genomicRegion
            //sequence = getResiduesFromFeature(gbolFeature)
            sequence = featureService.getResiduesWithAlterationsAndFrameshifts(gbolFeature)
        }
        return sequence
    }
    
    def getSequenceForFeatures(JSONObject inputObject, File outputFile=null) {
        log.debug "getSequenceForFeature: ${inputObject}"
        JSONArray featuresArray = inputObject.getJSONArray(FeatureStringEnum.FEATURES.value)
        String type = inputObject.getString(FeatureStringEnum.TYPE.value)
        int flank
        if (inputObject.has('flank')) {
            flank = inputObject.getInt("flank")
            log.debug "flank from request object: ${flank}"
        } else {
            flank = 0
        }

        for (int i = 0; i < featuresArray.length(); ++i) {
            JSONObject jsonFeature = featuresArray.getJSONObject(i)
            String uniqueName = jsonFeature.get(FeatureStringEnum.UNIQUENAME.value)
            Feature gbolFeature = Feature.findByUniqueName(uniqueName)
            String sequence = getSequenceForFeature(gbolFeature, type, flank)

            JSONObject outFeature = featureService.convertFeatureToJSON(gbolFeature)
            outFeature.put("residues", sequence)
            outFeature.put("uniquename", uniqueName)
            return outFeature
        }
    }
    
    def getGff3ForFeature(JSONObject inputObject, File outputFile) {
        log.debug "getGff3ForFeature"
        List<Feature> featuresToWrite = new ArrayList<>();
        JSONArray features = inputObject.getJSONArray(FeatureStringEnum.FEATURES.value)
        for (int i = 0; i < features.length(); ++i) {
            JSONObject jsonFeature = features.getJSONObject(i);
            String uniqueName = jsonFeature.getString(FeatureStringEnum.UNIQUENAME.value);
            Feature gbolFeature = Feature.findByUniqueName(uniqueName)
            gbolFeature = featureService.getTopLevelFeature(gbolFeature)
            featuresToWrite.add(gbolFeature);
        }
        gff3HandlerService.writeFeaturesToText(outputFile.absolutePath, featuresToWrite, grailsApplication.config.apollo.gff3.source as String)
    }
}
