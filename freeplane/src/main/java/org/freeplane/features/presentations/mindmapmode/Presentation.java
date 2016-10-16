package org.freeplane.features.presentations.mindmapmode;

public class Presentation implements NamedElement<Presentation>{
	private String name;
	public final CollectionModel<Slide> slides;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Presentation(String name) {
		super();
		this.name = name;
		slides = new CollectionModel<>(Slide.class);
	}
	
	public Presentation saveAs(String name) {
		return new Presentation(name);
	}
}