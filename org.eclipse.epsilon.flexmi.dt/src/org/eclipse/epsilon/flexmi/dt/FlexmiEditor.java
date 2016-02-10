package org.eclipse.epsilon.flexmi.dt;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.transform.TransformerException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource.Diagnostic;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.epsilon.flexmi.FlexmiResource;
import org.eclipse.epsilon.flexmi.FlexmiResourceFactory;
import org.eclipse.epsilon.flexmi.ParseWarning;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.MarkerUtilities;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.xml.sax.SAXParseException;

public class FlexmiEditor extends TextEditor {

	private ColorManager colorManager;
	protected Job parseModuleJob = null;
	protected FlexmiContentOutlinePage outlinePage = null;
	protected FlexmiResource resource = null;
	
	public FlexmiEditor() {
		super();
		setEditorContextMenuId("#TextEditorContext");
	    setRulerContextMenuId("editor.rulerMenu");
		colorManager = new ColorManager();
		setSourceViewerConfiguration(new XMLConfiguration(colorManager));
		setDocumentProvider(new XMLDocumentProvider());
	}
	
	@Override
	public void init(IEditorSite site, IEditorInput input)
			throws PartInitException {
		super.init(site, input);
		
		outlinePage = new FlexmiContentOutlinePage(this);
		
		final int delay = 1000;
		
		parseModuleJob = new Job("Parsing module") {
			
			protected int status = -1;
			
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				
				if (!isClosed()) {
					int textHashCode = getText().hashCode();
					if (status != textHashCode) {
						parseModule();
						status = textHashCode;
					}
					
					this.schedule(delay);
				}
				
				return Status.OK_STATUS;
			}
		};
		
		parseModuleJob.setSystem(true);
		parseModuleJob.schedule(delay);
		
	}
	
	@Override
	protected void doSetSelection(ISelection selection) {
		super.doSetSelection(selection);
		System.out.println(selection);
	}
	
	public void parseModule() {
		
		// Return early if the file is opened in an unexpected editor (e.g. in a Subclipse RemoteFileEditor)
		if (!(getEditorInput() instanceof FileEditorInput)) return;
		
		FileEditorInput fileInputEditor = (FileEditorInput) getEditorInput();
		IFile file = fileInputEditor.getFile();
		
		final IDocument doc = this.getDocumentProvider().getDocument(
				this.getEditorInput());
		
		// Replace tabs with spaces to match
		// column numbers produced by the parser
		String code = doc.get();
		code = code.replaceAll("\t", " ");
		SAXParseException parseException = null;
		
		ResourceSet resourceSet = new ResourceSetImpl();
		
		try {
			resourceSet.getPackageRegistry().put(EcorePackage.eINSTANCE.getNsURI(), EcorePackage.eINSTANCE);
			resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new FlexmiResourceFactory());
			resource = (FlexmiResource) resourceSet.createResource(URI.createFileURI(file.getLocation().toOSString()));
			resource.load(new ByteArrayInputStream(code.getBytes()), null);
		}
		catch (Exception ex) {
				
				if (ex instanceof RuntimeException) {
					if (ex.getCause() instanceof TransformerException) {
						if (ex.getCause().getCause() instanceof SAXParseException) {
							parseException = (SAXParseException) ex.getCause().getCause();
						}
					}
				}
				else {
					ex.printStackTrace();
				}
		}
		
		final String markerType = "org.eclipse.epsilon.flexmi.dt.problemmarker";
		
		// Update problem markers
		try {
			file.deleteMarkers(markerType, true, IResource.DEPTH_INFINITE);
			if (parseException != null) {
				createMarker(parseException.getMessage(), parseException.getLineNumber(), true, file, markerType);
			}
			else {
				for (Diagnostic warning : resource.getWarnings()) {
					createMarker(warning.getMessage(), warning.getLine(), false, file, markerType);
				}
				outlinePage.setResourceSet(resourceSet);
			}
			
		} catch (CoreException e1) {
			e1.printStackTrace();
		}
		
	}
	
	@SuppressWarnings("rawtypes")
	@Override
	public Object getAdapter(Class required) {
		if (IContentOutlinePage.class.equals(required)) {
			return outlinePage;
		}
		return super.getAdapter(required);
	}
	
	protected void createMarker(String message, int lineNumber, boolean error, IFile file, String markerType) throws CoreException {
		Map<String, Object> attr = new HashMap<String, Object>();
		attr.put(IMarker.LINE_NUMBER, lineNumber);
		attr.put(IMarker.MESSAGE, message);				
		int markerSeverity = IMarker.SEVERITY_WARNING;
		if (error) markerSeverity = IMarker.SEVERITY_ERROR;
		attr.put(IMarker.SEVERITY, markerSeverity);
		MarkerUtilities.createMarker(file, attr, markerType);
	}
	
	public boolean isClosed() {
		return this.getDocumentProvider() == null;
	}
	
	public String getText() {
		return this.getDocumentProvider().getDocument(
				this.getEditorInput()).get();
	}
	
	public FlexmiResource getResource() {
		return resource;
	}
	
	public void dispose() {
		colorManager.dispose();
		super.dispose();
	}

}
