package org.eclipse.epsilon.flexmi;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.logging.Level;

import javax.swing.plaf.basic.BasicInternalFrameTitlePane.MaximizeAction;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.ENamedElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.epsilon.emc.emf.InMemoryEmfModel;
import org.eclipse.epsilon.eol.EolModule;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.DefaultHandler;

public class FlexmiResource extends ResourceImpl {
	
	protected HashMap<String, List<EObject>> idCache = new HashMap<String, List<EObject>>();
	protected List<UnresolvedReference> unresolvedReferences = new ArrayList<UnresolvedReference>();
	protected Stack<EObject> stack = new Stack<EObject>();
	
	public static void main(String[] args) throws Exception {
		
		ResourceSet resourceSet = new ResourceSetImpl();
		resourceSet.getPackageRegistry().put(EcorePackage.eINSTANCE.getNsURI(), EcorePackage.eINSTANCE);
		resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new FlexmiResourceFactory());
		Resource resource = resourceSet.createResource(URI.createURI(FlexmiResource.class.getResource("sample.xml").toString()));
		resource.load(null);
		
		EolModule module = new EolModule();
		module.parse("EReference.all.first().eType.name.println();");
		module.getContext().getModelRepository().addModel(new InMemoryEmfModel("M", resource));
		module.execute();
	}
	
	public FlexmiResource(URI uri) {
		super(uri);
	}
	
	@Override
	public void load(Map<?, ?> options) throws IOException {
		try {
			loadImpl(options);
		}
		catch (IOException ioException) {
			throw ioException;
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
	
	public void loadImpl(Map<?, ?> options) throws Exception {
		getContents().clear();
		unresolvedReferences.clear();
		stack.clear();
		
		InputStream inputStream = getURIConverter().createInputStream(uri);
		SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
		DefaultHandler handler = new DefaultHandler() {
			
			@Override
			public void startDocument() throws SAXException {}
			
			@Override
			public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
				FlexmiResource.this.startElement(uri, localName, qName, attributes);
			}
			
			@Override
			public void endElement(String uri, String localName, String name)
					throws SAXException {
				FlexmiResource.this.endElement(uri, localName, name);
			}
			
			@Override
			public void processingInstruction(String key, String value)
					throws SAXException {
				System.out.println(key +"->" + value);
				if ("nsuri".equals(key)) {
					EPackage ePackage = EPackage.Registry.INSTANCE.getEPackage(value);
					if (ePackage != null) getResourceSet().getPackageRegistry().put(ePackage.getNsURI(), ePackage);
				}
			}
			
			@Override
			public void endDocument() throws SAXException {
				resolveReferences();
			}
		};
		saxParser.parse(inputStream, handler);
	}
	
	@SuppressWarnings("unchecked")
	public void resolveReferences() {
		
		List<UnresolvedReference> resolvedReferences = new ArrayList<UnresolvedReference>();
		
		for (UnresolvedReference unresolvedReference : unresolvedReferences) {
			
			EReference eReference = unresolvedReference.getEReference();
			if (eReference.isMany()) {
				for (String value : unresolvedReference.getValue().split(",")) {
					List<EObject> candidates = idCache.get(value.trim());
					if (candidates != null) {
						for (EObject candidate : candidates) {
							if (eReference.getEReferenceType().isInstance(candidate)) {
								((List<EObject>) unresolvedReference.getEObject().eGet(eReference)).add(candidate);
								resolvedReferences.add(unresolvedReference);
								break;
							}
						}
					}
				}
			}
			else {
				List<EObject> candidates = idCache.get(unresolvedReference.getValue());
				if (candidates != null) {
					for (EObject candidate : candidates) {
						if (eReference.getEReferenceType().isInstance(candidate)) {
							unresolvedReference.getEObject().eSet(eReference, candidate);
							resolvedReferences.add(unresolvedReference);
							break;
						}
					}
				}
			}
		}
		
		unresolvedReferences.removeAll(resolvedReferences);
		idCache.clear();
	}
	
	@SuppressWarnings("unchecked")
	public void startElement(String uri, String localName, String name, Attributes attributes) throws SAXException {
		EObject eObject = null;
		EClass eClass = null;
		if (stack.isEmpty()) {
			eClass = eClassForName(name);
			if (eClass != null) {
				eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
				getContents().add(eObject);
				setAttributes(eObject, attributes);
			}
			stack.push(eObject);
		}
		else {
			EObject parent = stack.peek();
			for (EReference eReference : parent.eClass().getEAllContainments()) {
				eClass = (EClass) eNamedElementForName(name, getAllSubtypes(eReference.getEReferenceType()));
				if (eClass != null) {
					eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
					if (eReference.isMany()) {
						((List<EObject>) parent.eGet(eReference)).add(eObject);
					}
					else {
						parent.eSet(eReference, eObject);
					}
					setAttributes(eObject, attributes);
					stack.push(eObject);
					break;
				}
				else {
					stack.push(null);
				}
			}
		}
	}
	
	public void endElement(String uri, String localName, String name) throws SAXException {
		stack.pop();
	}
	
	protected void setAttributes(EObject eObject, Attributes attributes) {
		
		List<EStructuralFeature> eStructuralFeatures = getCandidateStructuralFeaturesForAttribute(eObject.eClass());
		
		for (int i=0;i<attributes.getLength();i++) {
			String name = attributes.getLocalName(i);
			String value = attributes.getValue(i);
			
			EStructuralFeature sf = (EStructuralFeature) eNamedElementForName(name, eStructuralFeatures);
			if (sf != null) {
				eStructuralFeatures.remove(sf);
				if (sf instanceof EAttribute) {
					EAttribute eAttribute = (EAttribute) sf;
					Object eValue = eAttribute.getEAttributeType().getEPackage().getEFactoryInstance().createFromString(eAttribute.getEAttributeType(), value);
					eObject.eSet(eAttribute, eValue);
					if (eAttribute.isID() || "name".equalsIgnoreCase(eAttribute.getName())) {
						List<EObject> eObjects = idCache.get(value);
						if (eObjects == null) {
							eObjects = new ArrayList<EObject>();
							idCache.put(value, eObjects);
						}
						eObjects.add(eObject);
					}
				}
				else if (sf instanceof EReference) {
					EReference eReference = (EReference) sf;
					UnresolvedReference unresolvedReference = new UnresolvedReference();
					unresolvedReference.setEObject(eObject);
					unresolvedReference.seteReference(eReference);
					unresolvedReference.setValue(value);
					unresolvedReferences.add(unresolvedReference);
				}
			}
		}
	}
	
	protected List<EStructuralFeature> getCandidateStructuralFeaturesForAttribute(EClass eClass) {
		List<EStructuralFeature> eStructuralFeatures = new ArrayList<EStructuralFeature>();
		for (EStructuralFeature sf : eClass.getEAllStructuralFeatures()) {
			if (sf.isChangeable() && (sf instanceof EAttribute || ((sf instanceof EReference) && !((EReference) sf).isContainment()))) {
				eStructuralFeatures.add(sf);
			}
		}
		return eStructuralFeatures;
	}
	
	protected List<EClass> getAllConcreteEClasses() {
		List<EClass> eClasses = new ArrayList<EClass>();
		Iterator<Object> it = getResourceSet().getPackageRegistry().values().iterator();
		while (it.hasNext()) {
			EPackage ePackage = (EPackage) it.next();
			for (EClassifier eClassifier : ePackage.getEClassifiers()) {
				if (eClassifier instanceof EClass && !((EClass) eClassifier).isAbstract()) {
					eClasses.add((EClass) eClassifier);
				}
			}
		}
		return eClasses;
	}
	
	protected List<EClass> getAllSubtypes(EClass eClass) {
		List<EClass> allSubtypes = new ArrayList<EClass>();
		for (EClass candidate : getAllConcreteEClasses()) {
			if (candidate.getEAllSuperTypes().contains(eClass)) {
				allSubtypes.add(candidate);
			}
		}
		return allSubtypes;
	}
			
	protected EClass eClassForName(String name) {
		return (EClass) eNamedElementForName(name, getAllConcreteEClasses());
	}
	
	protected ENamedElement eNamedElementForName(String name, List<? extends ENamedElement> candidates) {
		ENamedElement eNamedElement = eNamedElementForName(name, candidates, false);
		if (eNamedElement == null) eNamedElement = eNamedElementForName(name, candidates, true);
		return eNamedElement;
	}
	
	protected ENamedElement eNamedElementForName(String name, List<? extends ENamedElement> candidates, boolean fuzzy) {
		
		if (fuzzy) {
			int maxLongestSubstring = 2;
			ENamedElement bestMatch = null;
			for (ENamedElement candidate : candidates) {
				int longestSubstring = longestSubstring(candidate.getName().toLowerCase(), name.toLowerCase());
				if (longestSubstring > maxLongestSubstring) {
					maxLongestSubstring = longestSubstring;
					bestMatch = candidate;
				}
			}
			return bestMatch;			
		}
		else {
			for (ENamedElement candidate : candidates) {
				if (candidate.getName().equalsIgnoreCase(name)) return candidate;
			}
		}
		
		return null;
	}
	
	public int longestSubstring(String first, String second) {
		if (first == null || second == null || first.length() == 0 || second.length() == 0) return 0;

		int maxLen = 0;
		int firstLength = first.length();
		int secondLength = second.length();
		int[][] table = new int[firstLength + 1][secondLength + 1];

		for (int f = 0; f <= firstLength; f++) table[f][0] = 0;
		for (int s = 0; s <= secondLength; s++) table[0][s] = 0;
		
		for (int i = 1; i <= firstLength; i++) {
			for (int j = 1; j <= secondLength; j++) {
				if (first.charAt(i - 1) == second.charAt(j - 1)) {
					if (i == 1 || j == 1) {
						table[i][j] = 1;
					} else {
						table[i][j] = table[i - 1][j - 1] + 1;
					}
					if (table[i][j] > maxLen) {
						maxLen = table[i][j];
					}
				}
			}
		}
		return maxLen;
	}
}