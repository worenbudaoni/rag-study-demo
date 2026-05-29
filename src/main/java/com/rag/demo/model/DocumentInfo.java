package com.rag.demo.model;

public class DocumentInfo {

    private String documentName;
    private int chunks;

    public DocumentInfo() {
    }

    public DocumentInfo(String documentName, int chunks) {
        this.documentName = documentName;
        this.chunks = chunks;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public int getChunks() {
        return chunks;
    }

    public void setChunks(int chunks) {
        this.chunks = chunks;
    }
}
