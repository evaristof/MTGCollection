package com.evaristof.mtgcollection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "CARD_IMAGE_HASH", uniqueConstraints = @UniqueConstraint(columnNames = {"SET_CODE", "COLLECTOR_NUMBER"}))
public class CardImageHash {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "SET_CODE", nullable = false, length = 16)
    private String setCode;

    @Column(name = "COLLECTOR_NUMBER", nullable = false, length = 32)
    private String collectorNumber;

    @Column(name = "CARD_NAME", nullable = false)
    private String cardName;

    @Column(name = "PHASH", nullable = false, length = 256)
    private String pHash;

    @Column(name = "MINIO_PATH", nullable = false)
    private String minioPath;

    @Column(name = "CNN_EMBEDDING", columnDefinition = "TEXT")
    private String cnnEmbedding;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSetCode() {
        return setCode;
    }

    public void setSetCode(String setCode) {
        this.setCode = setCode;
    }

    public String getCollectorNumber() {
        return collectorNumber;
    }

    public void setCollectorNumber(String collectorNumber) {
        this.collectorNumber = collectorNumber;
    }

    public String getCardName() {
        return cardName;
    }

    public void setCardName(String cardName) {
        this.cardName = cardName;
    }

    public String getPHash() {
        return pHash;
    }

    public void setPHash(String pHash) {
        this.pHash = pHash;
    }

    public String getMinioPath() {
        return minioPath;
    }

    public void setMinioPath(String minioPath) {
        this.minioPath = minioPath;
    }

    public String getCnnEmbedding() {
        return cnnEmbedding;
    }

    public void setCnnEmbedding(String cnnEmbedding) {
        this.cnnEmbedding = cnnEmbedding;
    }
}
