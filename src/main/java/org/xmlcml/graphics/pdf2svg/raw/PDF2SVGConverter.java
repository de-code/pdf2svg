package org.xmlcml.graphics.pdf2svg.raw;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.print.attribute.standard.PageRanges;

import nu.xom.Builder;
import nu.xom.Document;

import org.apache.log4j.Logger;
import org.apache.pdfbox.exceptions.CryptographyException;
import org.apache.pdfbox.exceptions.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.util.PDFStreamEngine;
import org.xmlcml.graphics.svg.SVGSVG;
import org.xmlcml.graphics.util.MenuSystem;

/**
 * Simple app to read PDF documents ... based on ... * PDFReader.java
 * 
 */
public class PDF2SVGConverter extends PDFStreamEngine {

	private static final double _DEFAULT_PAGE_WIDTH = 600.0;
	private static final double _DEFAULT_PAGE_HEIGHT = 800.0;
	private static final String DEFAULT_PUBLISHER_SET_XML = "org/xmlcml/graphics/pdf2svg/raw/publisherSet.xml";
	private final static Logger LOG = Logger.getLogger(PDF2SVGConverter.class);
	@SuppressWarnings("unused")
	private static final long serialVersionUID = 1L;

	public static final String PASSWORD = "-password";
	public static final String NONSEQ = "-nonseq";
	public static final String PAGES = "-pages";
	public static final String PUB = "-pub";
	public static final String OUTDIR = "-outdir";

	private String PDFpassword = "";
	private boolean useNonSeqParser = false;
	private String outputDirectory = ".";
	private PageRanges pageRanges = null;
	private Publisher publisher = null;

	private PDDocument document;
	private List<SVGSVG> svgPageList;
	private boolean fixFont = true;
	
	private AMIFontManager amiFontManager;
	private Map<String, AMIFont> amiFontMap;
	CodePointSet knownCodePointSet;
	CodePointSet newCodePointSet;
	private File outdir;
	private int iarg;
	private String publisherSetXmlResource = DEFAULT_PUBLISHER_SET_XML;
	private PublisherSet publisherSet;
	
	Double pageHeight = _DEFAULT_PAGE_HEIGHT;
	Double pageWidth = _DEFAULT_PAGE_WIDTH;

	private static void usage() {
		System.err
				.printf("Usage: pdf2svg [%s pw] [%s] [%s <page-ranges>] [%s dir] <input-file> ...%n%n"
						+ "  %s <password>  Password to decrypt the document (default none)%n"
						+ "  %s               Enables the new non-sequential parser%n"
						+ "  %s <page-ranges>  Restrict pages to be output (default all)%n"
						+ "  %s <publisher>   Use publisher-specific info%n"
						+ "  %s <dirname>     Location to write output pages (default '.')%n"
						+ "  <input-file>          The PDF document to be loaded%n",
						PASSWORD, NONSEQ, PAGES, PUB, OUTDIR, PASSWORD, NONSEQ,
						PAGES, OUTDIR);
		System.exit(1);
	}

	private void openPDFFile(String filename) throws Exception {

		PDFPage2SVGConverter drawer = new PDFPage2SVGConverter();
		System.out.printf("Parsing PDF file %s ...%n", filename);
		readDocument(filename, useNonSeqParser, PDFpassword);

		@SuppressWarnings("unchecked")
		List<PDPage> pages = document.getDocumentCatalog().getAllPages();

		PageRanges pr = pageRanges;
		if (pr == null) {
			pr = new PageRanges(String.format("1-%d", pages.size()));
		}

		createOutputDirectory();

		System.out.printf("Processing pages %s (of %d) ...%n", pr.toString(),
				pages.size()); 

		File infile = new File(filename);
		String basename = infile.getName().toLowerCase();
		if (basename.endsWith(".pdf"))
			basename = basename.substring(0, basename.length() - 4);

		int pageNumber = pr.next(0);
		List<File> outfileList = new ArrayList<File>();
		while (pageNumber > 0) {
			PDPage page = pages.get(pageNumber - 1);

			System.out.println("=== " + pageNumber + " ===");
			drawer.convertPageToSVG(page, this);

			File outfile = writeFile(drawer, basename, pageNumber);
			outfileList.add(outfile);

			pageNumber = pr.next(pageNumber);
		}

		reportHighCodePoints();
		reportNewFontFamilyNames();
		writeHTMLSystem(outfileList);
		reportPublisher();
	}

	private void reportPublisher() {
		if (publisher != null) {
			LOG.debug("PUB "+publisher.createElement().toXML());
		}
	}

	private void createOutputDirectory() {
		outdir = new File(outputDirectory);
		if (!outdir.exists())
			outdir.mkdirs();
		if (!outdir.isDirectory())
			throw new RuntimeException(String.format(
					"'%s' is not a directory!", outputDirectory));
	}

	private File writeFile(PDFPage2SVGConverter drawer, String basename,
			int pageNumber) throws IOException,
			UnsupportedEncodingException, FileNotFoundException {
		ensureSVGPageList();
		File outfile = new File(outdir, basename + "-page" + pageNumber + ".svg");
		System.out.printf("Writing output to file '%s'%n", outfile.getCanonicalPath());

		SVGSVG svgPage = drawer.getSVG();
		svgPageList.add(svgPage);
		SVGSerializer serializer = new SVGSerializer(new FileOutputStream(
				outfile), "UTF-8");
		Document document = svgPage.getDocument();
		document = (document == null) ? new Document(svgPage) : document;
		serializer.setIndent(1);
		serializer.write(document);
		return outfile;
	}

	private void reportNewFontFamilyNames() {
		FontFamilySet newFontFamilySet = amiFontManager.getNewFontFamilySet();
		LOG.debug("new fontFamilyNames: "+newFontFamilySet.createElement().toXML());
	}

	private void writeHTMLSystem(List<File> outfileList) {
		MenuSystem menuSystem = new MenuSystem(outdir);
		menuSystem.writeDisplayFiles(outfileList, "");
	}

	private void ensureSVGPageList() {
		if (svgPageList == null) {
			svgPageList = new ArrayList<SVGSVG>();
		}
	}

	private void readDocument(String filename, boolean useNonSeqParser,
			String password) throws IOException {
		File file = new File(filename);
		if (useNonSeqParser) {
			document = PDDocument.loadNonSeq(file, null, password);
		} else {
			document = PDDocument.load(file);
			if (document.isEncrypted()) {
				try {
					document.decrypt(password);
				} catch (InvalidPasswordException e) {
					System.err
							.printf("Error: The document in file '%s' is encrypted (use '-password' option).%n",
									filename);
					return;
				} catch (CryptographyException e) {
					System.err
							.printf("Error: Failed to decrypt document in file '%s'.%n",
									filename);
					return;
				}
			}
		}

	}

	public static void main(String[] args) throws Exception {

		PDF2SVGConverter converter = new PDF2SVGConverter();
		converter.run(args);

		System.exit(0);
	}

	public void run(String argString) {
		run(argString.split("[\\s+]"));
	}

	public void run(String... args) {

		if (args.length == 0)
			usage();

		for (iarg = 0; iarg < args.length; iarg++) {

			if (args[iarg].equals(PASSWORD)) {
				incrementArg(args);
				PDFpassword = args[iarg];
				continue;
			}

			if (args[iarg].equals(NONSEQ)) {
				useNonSeqParser = true;
				continue;
			}

			if (args[iarg].equals(OUTDIR)) {
				incrementArg(args);
				outputDirectory = args[iarg];
				continue;
			}

			if (args[iarg].equals(PAGES)) {
				incrementArg(args);
				pageRanges = new PageRanges(args[iarg]);
				continue;
			}
			if (args[iarg].equals(PUB)) {
				incrementArg(args);
				publisher = getPublisher(args[iarg]);
				continue;
			}

			try {
				this.openPDFFile(args[iarg]);
			} catch (Exception e) {
				throw new RuntimeException("Cannot parse PDF: " + args[iarg], e);
			}
		}
	}

	public Publisher getPublisher() {
		return publisher;
	}

	private Publisher getPublisher(String abbreviation) {
		ensurePublisherMaps();
		publisher = (publisherSet == null) ? null : publisherSet.getPublisherByAbbreviation(abbreviation);
		return publisher;
	}

	private void ensurePublisherMaps() {
		if (publisherSet == null && publisherSetXmlResource != null) {
			publisherSet = PublisherSet.readPublisherSet(publisherSetXmlResource );
		}
	}

	private void incrementArg(String... args) {
		iarg++;
		if (iarg >= args.length) {
			usage();
		}
	}

	public List<SVGSVG> getPageList() {
		ensureSVGPageList();
		return svgPageList;
	}

	private void reportHighCodePoints() {
		ensureCodePointSets();
		int newCodePointCount = newCodePointSet.size();
		if (newCodePointCount > 0) {
			LOG.debug("New High CodePoints: " + newCodePointSet.size());
			LOG.debug(newCodePointSet.createElementWithSortedIntegers().toXML());
		}
	}

	void ensureCodePointSets() {
		if (newCodePointSet == null) {
			newCodePointSet = new CodePointSet();
		}
		if (knownCodePointSet == null) {
			knownCodePointSet = CodePointSet.readCodePointSet(CodePointSet.KNOWN_HIGH_CODE_POINT_SET_XML); 
		}
	}

	public CodePointSet getKnownCodePointSet() {
		ensureCodePointSets();
		return knownCodePointSet;
	}

	public CodePointSet getNewCodePointSet() {
		ensureCodePointSets();
		return newCodePointSet;
	}

	public void setFixFont(boolean fixFont) {
		this.fixFont = fixFont;
	}

	public boolean isFixFont() {
		return fixFont ;
	}
	
	public AMIFontManager getAmiFontManager() {
		ensureAmiFontManager();
		return amiFontManager;
	}

	private void ensureAmiFontManager() {
		if (amiFontManager == null) {
			amiFontManager = new AMIFontManager();
			amiFontMap = AMIFontManager.readAmiFonts();
			for (String fontName : amiFontMap.keySet()) {
				AMIFont font = amiFontMap.get(fontName);
			}
		}
	}

	Map<String, AMIFont> getAMIFontMap() {
		ensureAmiFontManager();
		return amiFontMap;
	}
}