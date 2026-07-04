package com.evaristof.mtgcollection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * Persisted Bag-of-Visual-Words vocabulary for the card scanner. A single row
 * (id = 1) holds the k-means visual vocabulary (VOCAB_SIZE x 32 float centers)
 * serialized to bytes, plus its dimensions. Persisting it — together with each
 * card's histogram on {@link CardImageHash} — lets the scanner load its model
 * at startup instead of retraining/re-extracting from every reference image.
 */
@Entity
@Table(name = "SCANNER_MODEL")
public class ScannerModel {

    /** Fixed singleton id. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "ID")
    private Long id;

    /** Number of visual words (vocabulary rows). */
    @Column(name = "VOCAB_SIZE", nullable = false)
    private int vocabSize;

    /** Descriptor dimensionality (columns), i.e. 32 for ORB. */
    @Column(name = "VOCAB_COLS", nullable = false)
    private int vocabCols;

    /** Row-major float32 vocabulary centers, little-endian. */
    @Lob
    @Column(name = "VOCABULARY", nullable = false)
    private byte[] vocabulary;

    public ScannerModel() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getVocabSize() {
        return vocabSize;
    }

    public void setVocabSize(int vocabSize) {
        this.vocabSize = vocabSize;
    }

    public int getVocabCols() {
        return vocabCols;
    }

    public void setVocabCols(int vocabCols) {
        this.vocabCols = vocabCols;
    }

    public byte[] getVocabulary() {
        return vocabulary;
    }

    public void setVocabulary(byte[] vocabulary) {
        this.vocabulary = vocabulary;
    }
}
