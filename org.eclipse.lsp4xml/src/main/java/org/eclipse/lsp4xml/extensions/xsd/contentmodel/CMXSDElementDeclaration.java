/**
 *  Copyright (c) 2018 Angelo ZERR
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v2.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v20.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.lsp4xml.extensions.xsd.contentmodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import org.apache.xerces.impl.dv.xs.XSSimpleTypeDecl;
import org.apache.xerces.impl.xs.SchemaGrammar;
import org.apache.xerces.impl.xs.SubstitutionGroupHandler;
import org.apache.xerces.impl.xs.XSComplexTypeDecl;
import org.apache.xerces.impl.xs.XSElementDecl;
import org.apache.xerces.impl.xs.models.CMBuilder;
import org.apache.xerces.impl.xs.models.XS11AllCM;
import org.apache.xerces.impl.xs.models.XSCMValidator;
import org.apache.xerces.xni.QName;
import org.apache.xerces.xs.XSAttributeUse;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSModelGroup;
import org.apache.xerces.xs.XSObject;
import org.apache.xerces.xs.XSObjectList;
import org.apache.xerces.xs.XSParticle;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTerm;
import org.apache.xerces.xs.XSTypeDefinition;
import org.apache.xerces.xs.XSWildcard;
import org.eclipse.lsp4xml.dom.DOMElement;
import org.eclipse.lsp4xml.extensions.contentmodel.model.CMAttributeDeclaration;
import org.eclipse.lsp4xml.extensions.contentmodel.model.CMElementDeclaration;
import org.eclipse.lsp4xml.utils.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XSD element declaration implementation.
 *
 */
public class CMXSDElementDeclaration implements CMElementDeclaration {

	private static final short PC_UNKWOWN = -1;

	private final CMXSDDocument document;

	private final XSElementDeclaration elementDeclaration;

	private Collection<CMAttributeDeclaration> attributes;

	private Collection<CMElementDeclaration> elements;

	private String documentation;

	public CMXSDElementDeclaration(CMXSDDocument document, XSElementDeclaration elementDeclaration) {
		this.document = document;
		this.elementDeclaration = elementDeclaration;
	}

	@Override
	public String getName() {
		return elementDeclaration.getName();
	}

	@Override
	public String getNamespace() {
		return elementDeclaration.getNamespace();
	}

	@Override
	public Collection<CMAttributeDeclaration> getAttributes() {
		if (attributes == null) {
			attributes = new ArrayList<>();
			collectAttributesDeclaration(elementDeclaration, attributes);
		}
		return attributes;
	}

	private void collectAttributesDeclaration(XSElementDeclaration elementDecl,
			Collection<CMAttributeDeclaration> attributes) {
		XSTypeDefinition typeDefinition = elementDecl.getTypeDefinition();
		switch (typeDefinition.getTypeCategory()) {
		case XSTypeDefinition.SIMPLE_TYPE:
			// TODO...
			break;
		case XSTypeDefinition.COMPLEX_TYPE:
			collectAttributesDeclaration((XSComplexTypeDefinition) typeDefinition, attributes);
			break;
		}
	}

	private void collectAttributesDeclaration(XSComplexTypeDefinition typeDefinition,
			Collection<CMAttributeDeclaration> attributes) {
		XSObjectList list = typeDefinition.getAttributeUses();
		if (list != null) {
			for (int i = 0; i < list.getLength(); i++) {
				XSObject object = list.item(i);
				if (object.getType() == XSConstants.ATTRIBUTE_USE) {
					XSAttributeUse attributeUse = (XSAttributeUse) object;
					attributes.add(new CMXSDAttributeDeclaration(attributeUse));
				}
			}
		}
	}

	@Override
	public Collection<CMElementDeclaration> getElements() {
		if (elements == null) {
			elements = new ArrayList<>();
			collectElementsDeclaration(elementDeclaration, elements);
		}
		return elements;
	}

	@Override
	public Collection<CMElementDeclaration> getPossibleElements(DOMElement parentElement, int offset) {
		XSTypeDefinition typeDefinition = elementDeclaration.getTypeDefinition();
		if (typeDefinition != null && typeDefinition.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) {
			// The type definition is complex (ex: xs:all; xs:sequence), returns list of
			// element declaration according those XML Schema constraints

			// Initialize Xerces validator
			CMBuilder cmBuilder = document.getCMBuilder();
			XSCMValidator validator = ((XSComplexTypeDecl) typeDefinition).getContentModel(cmBuilder);
			if (validator == null) {
				return Collections.emptyList();
			}

			SubstitutionGroupHandler handler = new SubstitutionGroupHandler(document);
			// Compute list of child element (QName)
			List<QName> qNames = toQNames(parentElement, offset);
			// Loop for each element (QName) and check if it is valid according the XML
			// Schema constraint
			int[] states = validator.startContentModel();
			// Xerces has a bug with xs:all and xs:element maxOccurs="unbound"
			// see https://issues.apache.org/jira/browse/XERCESJ-1710, to fix it we add the
			// missing xs:element in a set
			boolean xsALl11 = isXSAll11(validator);
			Set<XSElementDeclaration> missingEltDeclForXSALL11 = null;
			for (QName elementName : qNames) {
				Object decl = validator.oneTransition(elementName, states, handler, document);
				if (decl == null) {
					// the current elemntName doesn't match the XSD constraints, return an empty
					// list
					return Collections.emptyList();
				}
				if (xsALl11) {
					// Check if the xs:element has defined a maxOccurs="unbound"
					if (isMaxOccursUnbounded11(decl)) {
						if (missingEltDeclForXSALL11 == null) {
							missingEltDeclForXSALL11 = new HashSet<>();
						}
						missingEltDeclForXSALL11.add((XSElementDeclaration) decl);
					}
				}
			}
			// At this step, all child elements are valid, the call of
			// XSCMValidator#oneTransition has updated the states flag.
			// Collect the next valid elements according the XML Schema constraints
			Vector<?> result = validator.whatCanGoHere(states);
			if (result.isEmpty() && missingEltDeclForXSALL11 == null) {
				return Collections.emptyList();
			}

			// Compute list of possible elements
			Collection<CMElementDeclaration> possibleElements = new HashSet<>();
			for (Object object : result) {
				if (object instanceof XSElementDeclaration) {
					XSElementDeclaration elementDecl = (XSElementDeclaration) object;
					document.collectElement(elementDecl, possibleElements);
					// Remove the element declaration from the missing xs:element for xs:all 1.1
					if (missingEltDeclForXSALL11 != null) {
						missingEltDeclForXSALL11.remove(elementDecl);
					}

					// Collect substitution group
					XSObjectList group = document.getSubstitutionGroup(elementDecl);
					if (group != null) {
						for (int i = 0; i < group.getLength(); i++) {
							XSElementDeclaration o = (XSElementDeclaration) group.item(i);
							document.collectElement(o, possibleElements);
						}
					}
				} else {
					// case with xs:any. Ex:
					// <xs:sequence>
					// <xs:any maxOccurs="2" processContents="lax" />
					// </xs:sequence>
					Collection<CMElementDeclaration> anyElements = getXSAnyElements(object);
					if (anyElements != null) {
						return anyElements;
					}
				}
			}

			// Add missing xs:element for xs:all 1.1
			if (missingEltDeclForXSALL11 != null) {
				for (XSElementDeclaration decl : missingEltDeclForXSALL11) {
					document.collectElement(decl, possibleElements);
				}
			}
			return possibleElements;
		}
		return getElements();
	}

	/**
	 * Returns true if the given validator is a xs:all for XSD 1.1 and false
	 * otherwise.
	 * 
	 * @param validator the CM Xerces validator
	 * @return true if the given validator is a xs:all for XSD 1.1 and false
	 *         otherwise.
	 */
	private static boolean isXSAll11(XSCMValidator validator) {
		return validator instanceof XS11AllCM;
	}

	/**
	 * Returns true if the current xs:element defined a maxOccurs="unbound" and
	 * false otherwise.
	 * 
	 * @param decl the xs:element
	 * @return true if the current xs:element defined a maxOccurs="unbound" and
	 *         false otherwise.
	 */
	private static boolean isMaxOccursUnbounded11(Object decl) {
		if (!(decl instanceof XSElementDecl)) {
			return false;
		}
		// Get the xs:all type
		XSElementDecl elementDecl = (XSElementDecl) decl;
		XSComplexTypeDefinition allType = elementDecl.getEnclosingCTDefinition();
		if (allType == null || allType.getParticle() == null || allType.getParticle().getTerm() == null
				|| !(allType.getParticle().getTerm() instanceof XSModelGroup)) {
			return false;
		}
		XSModelGroup group = (XSModelGroup) allType.getParticle().getTerm();
		// Loop for each particle to retrieve the proper particle of the xs:element
		XSObjectList particles = group.getParticles();
		for (int i = 0; i < particles.getLength(); i++) {
			XSParticle particle = (XSParticle) particles.item(i);
			if (elementDecl.equals(particle.getTerm())) {
				return particle.getMaxOccursUnbounded();
			}
		}
		return false;
	}

	/**
	 * Returns the possible elements declaration if the given declaration is an
	 * xs:any and null otherwise.
	 * 
	 * @param declaration the element, wildcard declaration.
	 * @return the possible elements declaration if the given declaration is an
	 *         xs:any and null otherwise.
	 * 
	 */
	private Collection<CMElementDeclaration> getXSAnyElements(Object declaration) {
		short processContents = getXSAnyProcessContents(declaration);
		if (processContents != PC_UNKWOWN) {
			// xs:any
			switch (processContents) {
			case XSWildcard.PC_STRICT:
				// <xs:any processContents="strict" />
				// only global element declaration from the XML Schema are allowed
				return document.getElements();
			default:
				// <xs:any processContents="lax" /> or <xs:any processContents="skip" />
				// all tags are allowed.
				return ANY_ELEMENT_DECLARATIONS;
			}
		}
		return null;
	}

	/**
	 * Returns the value of the xs:any/@processContents if the given element is a
	 * xs:any and {@link #PC_UNKWOWN} otherwise.
	 * 
	 * @param declaration the element, wildcard declaration.
	 * @return the value of the xs:any/@processContents if the given element is a
	 *         xs:any and {@link #PC_UNKWOWN} otherwise.
	 */
	private static short getXSAnyProcessContents(Object declaration) {
		if (declaration instanceof XSWildcard) {
			XSWildcard wildcard = (XSWildcard) declaration;
			if ((wildcard.getConstraintType() == XSWildcard.NSCONSTRAINT_ANY)) {
				return wildcard.getProcessContents();
			}
		}
		return PC_UNKWOWN;
	}

	/**
	 * Returns list of element (QName) of child elements of the given parent element
	 * upon the given offset
	 * 
	 * @param parentElement the parent element
	 * @param offset        the offset where child element must be belong to
	 * @return list of element (QName) of child elements of the given parent element
	 *         upon the given offset
	 */
	private static List<QName> toQNames(DOMElement parentElement, int offset) {
		if (parentElement == null || !parentElement.hasChildNodes()) {
			return Collections.emptyList();
		}
		List<QName> qNames = new ArrayList<>();
		NodeList children = parentElement.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				// child node is an element
				DOMElement element = (DOMElement) child;
				if (element.getEnd() > offset) {
					// child element is after the given offset, stop the computing
					break;
				}
				if (!element.isClosed()) {
					// Element is not closed, ignore it
					continue;
				}
				qNames.add(createQName(element));
			}
		}
		return qNames;
	}

	private static QName createQName(Element tag) {
		// intern must be called since Xerces uses == to compare String ?
		// -> see
		// https://github.com/apache/xerces2-j/blob/trunk/src/org/apache/xerces/impl/xs/SubstitutionGroupHandler.java#L55
		String namespace = tag.getNamespaceURI();
		return new QName(tag.getPrefix(), tag.getLocalName().intern(), tag.getTagName().intern(),
				StringUtils.isEmpty(namespace) ? null : namespace.intern());
	}

	private void collectElementsDeclaration(XSElementDeclaration elementDecl,
			Collection<CMElementDeclaration> elements) {
		XSTypeDefinition typeDefinition = elementDecl.getTypeDefinition();
		switch (typeDefinition.getTypeCategory()) {
		case XSTypeDefinition.SIMPLE_TYPE:
			// TODO...
			break;
		case XSTypeDefinition.COMPLEX_TYPE:
			collectElementsDeclaration((XSComplexTypeDefinition) typeDefinition, elements);
			break;
		}
	}

	private void collectElementsDeclaration(XSComplexTypeDefinition typeDefinition,
			Collection<CMElementDeclaration> elements) {
		XSParticle particle = typeDefinition.getParticle();
		if (particle != null) {
			collectElementsDeclaration(particle.getTerm(), elements);
		}
	}

	@SuppressWarnings("unchecked")
	private void collectElementsDeclaration(XSTerm term, Collection<CMElementDeclaration> elements) {
		if (term == null) {
			return;
		}
		switch (term.getType()) {
		case XSConstants.WILDCARD:
			// XSWildcard wildcard = (XSWildcard) term;
			// ex : xsd:any
			document.getElements().forEach(e -> {
				if (!elements.contains(e)) {
					elements.add(e);
				}
			});
			break;
		case XSConstants.MODEL_GROUP:
			XSObjectList particles = ((XSModelGroup) term).getParticles();
			particles.forEach(p -> collectElementsDeclaration(((XSParticle) p).getTerm(), elements));
			break;
		case XSConstants.ELEMENT_DECLARATION:
			XSElementDeclaration elementDeclaration = (XSElementDeclaration) term;
			document.collectElement(elementDeclaration, elements);
			break;
		}
	}

	@Override
	public String getDocumentation() {
		if (documentation != null) {
			return documentation;
		}
		// Try get xs:annotation from the element declaration or type
		XSObjectList annotations = getAnnotations();
		documentation = XSDAnnotationModel.getDocumentation(annotations);
		return documentation;
	}

	/**
	 * Returns list of xs:annotation from the element declaration or type
	 * declaration.
	 * 
	 * @return list of xs:annotation from the element declaration or type
	 *         declaration.
	 */
	private XSObjectList getAnnotations() {
		// Try get xs:annotation from the element declaration
		XSObjectList annotation = elementDeclaration.getAnnotations();
		if (annotation != null && annotation.getLength() > 0) {
			return annotation;
		}
		// Try get xs:annotation from the type of element declaration
		XSTypeDefinition typeDefinition = elementDeclaration.getTypeDefinition();
		if (typeDefinition == null) {
			return null;
		}
		if (typeDefinition.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) {
			return ((XSComplexTypeDecl) typeDefinition).getAnnotations();
		} else if (typeDefinition.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE) {
			return ((XSSimpleTypeDecl) typeDefinition).getAnnotations();
		}
		return null;
	}

	@Override
	public CMElementDeclaration findCMElement(String tag, String namespace) {
		for (CMElementDeclaration cmElement : getElements()) {
			if (cmElement.getName().equals(tag)) {
				return cmElement;
			}
		}
		return null;
	}

	@Override
	public CMAttributeDeclaration findCMAttribute(String attributeName) {
		for (CMAttributeDeclaration cmAttribute : getAttributes()) {
			if (cmAttribute.getName().equals(attributeName)) {
				return cmAttribute;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return getName();
	}

	@Override
	public boolean isEmpty() {
		XSTypeDefinition typeDefinition = elementDeclaration.getTypeDefinition();
		if (typeDefinition != null && typeDefinition.getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) {
			XSComplexTypeDefinition complexTypeDefinition = (XSComplexTypeDefinition) typeDefinition;
			return complexTypeDefinition.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_EMPTY;
		}
		return false;
	}

	@Override
	public Collection<String> getEnumerationValues() {
		XSTypeDefinition typeDefinition = elementDeclaration.getTypeDefinition();
		if (typeDefinition != null && typeDefinition.getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE) {
			return CMXSDDocument.getEnumerationValues((XSSimpleTypeDefinition) typeDefinition);
		}
		return Collections.emptyList();
	}

	XSElementDeclaration getElementDeclaration() {
		return elementDeclaration;
	}

	@Override
	public String getDocumentURI() {
		SchemaGrammar schemaGrammar = document.getOwnerSchemaGrammar(elementDeclaration);
		return CMXSDDocument.getSchemaURI(schemaGrammar);
	}

}
