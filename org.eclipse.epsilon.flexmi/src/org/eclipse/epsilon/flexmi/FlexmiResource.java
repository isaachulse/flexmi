package org.eclipse.epsilon.flexmi;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

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
import org.eclipse.epsilon.flexmi.xml.Location;
import org.eclipse.epsilon.flexmi.xml.PseudoSAXParser;
import org.eclipse.epsilon.flexmi.xml.PseudoSAXParser.Handler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.ProcessingInstruction;
import org.w3c.dom.Text;

public class FlexmiResource extends ResourceImpl implements Handler {
	
	public static final String OPTION_FUZZY_CONTAINMENT_MATCHING = "fuzzyContainmentMatching";
	public static final String OPTION_ORPHANS_AS_TOP_LEVEL = "orphansAsTopLevel";
	public static final String OPTION_FUZZY_MATCHING_THRESHOLD = "fuzzyMatchingThreshold";
	
	protected EObjectIdManager eObjectIdManager = new EObjectIdManager();
	protected EObjectTraceManager eObjectTraceManager = new EObjectTraceManager();
	protected List<UnresolvedReference> unresolvedReferences = new ArrayList<UnresolvedReference>();
	protected Stack<Object> stack = new Stack<Object>();
	protected Node currentNode = null;
	protected List<String> scripts = new ArrayList<String>();
	protected HashMap<String, EClass> eClassCache = new HashMap<String, EClass>();
	protected HashMap<EClass, List<EClass>> allSubtypesCache = new HashMap<EClass, List<EClass>>();
	protected StringSimilarityProvider stringSimilarityProvider = new DefaultStringSimilarityProvider();
	
	protected boolean fuzzyContainmentSlotMatching = true;
	protected boolean orphansAsTopLevel = false;
	protected int fuzzyMatchingThreshold = 2;
	
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
	protected void doLoad(InputStream inputStream, Map<?, ?> options)
			throws IOException {
		try {
			doLoadImpl(inputStream, options);
		}
		catch (IOException ioException) {
			ioException.printStackTrace();
			throw ioException;
		}
		catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException(ex);
		}
	}
	
	protected void processOption(String key, String value) {
		try {
			if (OPTION_FUZZY_CONTAINMENT_MATCHING.equalsIgnoreCase(key)) {
				fuzzyContainmentSlotMatching = Boolean.parseBoolean(value);
			}
			else if (OPTION_ORPHANS_AS_TOP_LEVEL.equalsIgnoreCase(key)) {
				orphansAsTopLevel = Boolean.parseBoolean(value);
			}
			else if (OPTION_FUZZY_MATCHING_THRESHOLD.equalsIgnoreCase(key)) {
				fuzzyMatchingThreshold = Integer.parseInt(value);
			}
			else throw new Exception("Unknown option");
		}
		catch (Exception ex) {
			addParseWarning("Could not process option " + key + ": " + ex.getMessage());
		}
	}
	
	public void doLoadImpl(InputStream inputStream, Map<?, ?> options) throws Exception {
		getContents().clear();
		unresolvedReferences.clear();
		stack.clear();
		scripts.clear();
		eClassCache.clear();
		allSubtypesCache.clear();
		eObjectIdManager = new EObjectIdManager();
		
		if (options != null) {
			for (Object key : options.keySet()) {
				processOption(key + "", options.get(key) + "");
			}
		}
		
		new PseudoSAXParser().parse(inputStream, this);
	}
	
	@Override
	public void startDocument(Document document) {}

	@Override
	public void startElement(Element element) {
		
		currentNode = element;
		String name = element.getNodeName();
		
		//Remove prefixes
		//TODO: Add option to disable this
		if (name.indexOf(":") > -1) {
			name = name.substring(name.indexOf(":")+1);
		}
		
		EObject eObject = null;
		EClass eClass = null;
		
		// We're at the root or we treat orphan elements as top-level
		if (stack.isEmpty() || (stack.peek() == null && orphansAsTopLevel)) {
			eClass = eClassForName(name);
			if (eClass != null) {
				eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
				getContents().add(eObject);
				setAttributes(eObject, element);
			}
			else {
				addParseWarning("Could not map element " + name + " to an EObject");
			}
			stack.push(eObject);
		}
		else {
			Object peek = stack.peek();
			
			// We find an orphan elmeent but don't treat it as top-level
			if (peek == null) {
				if (peek == null) {
					stack.push(null);
					addParseWarning("Could not map element " + name + " to an EObject");
					return;
				}
			}
			// The parent is an already-established containment slot
			else if (peek instanceof EReferenceSlot) {
				EReferenceSlot containmentSlot = (EReferenceSlot) peek;
				eClass = (EClass) eNamedElementForName(name, getAllSubtypes(containmentSlot.getEReference().getEReferenceType()));
				
				if (eClass != null) {
					eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
					containmentSlot.newValue(eObject);
					stack.push(eObject);
					setAttributes(eObject, element);
				}
				else {
					stack.push(null);
					addParseWarning("Could not map element " + name + " to an EObject");
				}
			}
			// The parent is an EObject
			else if (peek instanceof EObject) {
				EObject parent = (EObject) peek;
				
				if (element.getAttributes().getLength() == 0 && element.getChildNodes().getLength() == 1 && element.getFirstChild() instanceof Text) {
					EAttribute eAttribute = (EAttribute) eNamedElementForName(name, parent.eClass().getEAllAttributes());
					
					if (eAttribute != null) {
						setEAttributeValue(parent, eAttribute, name, element.getTextContent().trim());
						stack.push(null);
						return;
					}
				}
				
				EReference containment = null;
				
				// No attributes -> Check whether there is a containment reference with that name
				if (element.getAttributes().getLength() == 0) {
					if (fuzzyContainmentSlotMatching) {
						containment = (EReference) eNamedElementForName(name, parent.eClass().getEAllContainments());
					}
					else {
						containment = (EReference) eNamedElementForName(name, parent.eClass().getEAllContainments(), false);				
					}
					if (containment != null) {
						EReferenceSlot containmentSlot = new EReferenceSlot(containment, parent);
						stack.push(containmentSlot);
						return;
					}
				}
				
				// No containment references found
				// Find potential types for the element
				Set<EClass> candidates = new HashSet<EClass>();
				for (EReference eReference : parent.eClass().getEAllContainments()) {
					candidates.addAll(getAllSubtypes(eReference.getEReferenceType()));				
				}
				
				// Get the best match and an appropriate containment reference
				eClass = (EClass) eNamedElementForName(name, candidates);
				if (eClass != null) {
					for (EReference eReference : parent.eClass().getEAllContainments()) {
						if (getAllSubtypes(eReference.getEReferenceType()).contains(eClass)) {
							containment = eReference;
							break;
						}
					}
				}
				
				// Found an appropriate containment reference
				if (containment != null) {
					eObject = eClass.getEPackage().getEFactoryInstance().create(eClass);
					if (containment.isMany()) {
						((List<EObject>) parent.eGet(containment)).add(eObject);
					}
					else {
						parent.eSet(containment, eObject);
					}
					setAttributes(eObject, element);
					stack.push(eObject);
				}
				// No luck - add warning
				else {
					stack.push(null);
					addParseWarning("Could not map element " + name + " to an EObject");
				}
			}
		}
	}

	@Override
	public void endElement(Element element) {
		Object object = stack.pop();
		if (object != null && object instanceof EObject) {
			EObject eObject = (EObject) object;
			eObjectTraceManager.trace(eObject, getLineNumber(element));
		}
	}

	@Override
	public void processingInstruction(ProcessingInstruction processingInstruction) {
		currentNode = processingInstruction;
		
		String key = processingInstruction.getTarget();
		String value = processingInstruction.getData();
		
		if ("nsuri".equalsIgnoreCase(key)) {
			EPackage ePackage = EPackage.Registry.INSTANCE.getEPackage(value);
			if (ePackage != null) getResourceSet().getPackageRegistry().put(ePackage.getNsURI(), ePackage);
			else addParseWarning("Failed to locate EPackage for nsURI " + value + " ");
		}
		else if ("eol".equalsIgnoreCase(key)) {
			scripts.add(value);
		}
		else processOption(key, value);
	}

	@Override
	public void endDocument(Document document) {
		resolveReferences();
	}
	
	public List<UnresolvedReference> getUnresolvedReferences() {
		return unresolvedReferences;
	}
	
	protected void addParseWarning(String message) {
		addParseWarning(message, getLineNumber(currentNode));
	}
	
	protected void addParseWarning(String message, int line) {
		getWarnings().add(new FlexmiDiagnostic(message, line, this));
	}
	
	@SuppressWarnings("unchecked")
	protected void resolveReferences() {
		
		List<UnresolvedReference> resolvedReferences = new ArrayList<UnresolvedReference>();
		
		for (UnresolvedReference unresolvedReference : unresolvedReferences) {
			EReference eReference = unresolvedReference.getEReference();
			if (eReference.isMany()) {
				
				if ("*".equals(unresolvedReference.getValue())) {
					Iterator<EObject> it = this.getAllContents();
					while (it.hasNext()) {
						EObject candidate = it.next();
						if (eReference.getEReferenceType().isInstance(candidate)) {
							new EReferenceSlot(eReference, unresolvedReference.getEObject()).newValue(candidate);
						}
					}
					resolvedReferences.add(unresolvedReference);
				}
				else {
					resolveReference(unresolvedReference, resolvedReferences);
				}
			}
			else {
				resolveReference(unresolvedReference, resolvedReferences);
			}
		}
		
		unresolvedReferences.removeAll(resolvedReferences);
		for (UnresolvedReference reference : unresolvedReferences) {
			addParseWarning("Could not resolve target " + reference.getValue() + " for reference " + reference.getAttributeName() + " (" + reference.getEReference().getName() + ")", reference.getLine());
		}
		eObjectIdManager = new EObjectIdManager();
	}
	
	protected void resolveReference(UnresolvedReference unresolvedReference, List<UnresolvedReference> resolvedReferences) {
		List<EObject> candidates = eObjectIdManager.getEObjectsById(unresolvedReference.getValue());
		if (unresolvedReference.resolve(candidates)) {
			resolvedReferences.add(unresolvedReference);
		}
	}
	
	protected int getLineNumber(Node node) {
		Location location = (Location) node.getUserData(Location.ID);
		if (location != null) {
			return location.getStartLine();
		}
		return 0;
	}
	
	protected void setAttributes(EObject eObject, Element element) {
		
		NamedNodeMap attributes = element.getAttributes();
		List<EStructuralFeature> eStructuralFeatures = getCandidateStructuralFeaturesForAttribute(eObject.eClass());
		eObjectTraceManager.trace(eObject, getLineNumber(element));
		
		for (int i=0;i<attributes.getLength();i++) {
			
			String name = attributes.item(i).getNodeName();
			String value = attributes.item(i).getNodeValue();
			
			EStructuralFeature sf = (EStructuralFeature) eNamedElementForName(name, eStructuralFeatures);
			if (sf != null) {
				eStructuralFeatures.remove(sf);
				if (sf instanceof EAttribute) {
					setEAttributeValue(eObject, (EAttribute) sf, name, value);
				}
				else if (sf instanceof EReference) {
					EReference eReference = (EReference) sf;
					if (eReference.isMany()) {
						for (String valuePart : value.split(",")) {
							unresolvedReferences.add(new UnresolvedReference(eObject, eReference, name, valuePart.trim(), getLineNumber(element)));
						}
					}
					else {
						unresolvedReferences.add(new UnresolvedReference(eObject, eReference, name, value, getLineNumber(element)));
					}
				}
			}
			else {
				addParseWarning("Could not map attribute " + name + " to a structural feature of " + eObject.eClass().getName());
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	protected void setEAttributeValue(EObject eObject, EAttribute eAttribute, String attributeName, String value) {
		if (eAttribute.isMany()) {
			for (String valuePart : value.split(",")) {
				Object eValue = getEValue(eAttribute, attributeName, valuePart.trim());
				if (eValue == null) continue;
				((List<Object>) eObject.eGet(eAttribute)).add(eValue);
			}
		}
		else {
			Object eValue = getEValue(eAttribute, attributeName, value);
			if (eValue == null) return;
			eObject.eSet(eAttribute, eValue);
			if (eAttribute.isID() || "name".equalsIgnoreCase(eAttribute.getName())) {
				eObjectIdManager.setEObjectId(eObject, value);
			}
		}
	}
	
	protected Object getEValue(EAttribute eAttribute, String attributeName, String value) {
		try {
			return eAttribute.getEAttributeType().getEPackage().getEFactoryInstance().createFromString(eAttribute.getEAttributeType(), value);
		}
		catch (Exception ex) {
			addParseWarning(ex.getMessage() + " in the value of " + attributeName);
			return null;
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
		List<EClass> allSubtypes = allSubtypesCache.get(eClass);
		if (allSubtypes == null) {
			allSubtypes = new ArrayList<EClass>();
			for (EClass candidate : getAllConcreteEClasses()) {
				if (candidate.getEAllSuperTypes().contains(eClass)) {
					allSubtypes.add(candidate);
				}
			}
			if (!eClass.isAbstract()) allSubtypes.add(eClass);
			allSubtypesCache.put(eClass, allSubtypes);
		}
		return allSubtypes;
	}
	
	protected EClass eClassForName(String name) {
		EClass eClass = eClassCache.get(name);
		if (eClass == null) {
			eClass = (EClass) eNamedElementForName(name, getAllConcreteEClasses());
			eClassCache.put(name, eClass);
		}
		return eClass;
		
	}
	
	protected ENamedElement eNamedElementForName(String name, Collection<? extends ENamedElement> candidates) {
		ENamedElement eNamedElement = eNamedElementForName(name, candidates, false);
		if (eNamedElement == null) eNamedElement = eNamedElementForName(name, candidates, true);
		return eNamedElement;
	}
	
	public EObjectTraceManager getEObjectTraceManager() {
		return eObjectTraceManager;
	}
	
	protected ENamedElement eNamedElementForName(String name, Collection<? extends ENamedElement> candidates, boolean fuzzy) {
		
		if (fuzzy) {
			int maxSimilarity = fuzzyMatchingThreshold;
			ENamedElement bestMatch = null;
			for (ENamedElement candidate : candidates) {
				int similarity = stringSimilarityProvider.getSimilarity(candidate.getName().toLowerCase(), name.toLowerCase());
				if (similarity > maxSimilarity) {
					maxSimilarity = similarity;
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

}
