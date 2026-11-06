package com.bazaar.catalog.product;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_image")
public class ProductImage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "alt_text", length = 160)
    private String altText;

    @Column(nullable = false)
    private int position;

    protected ProductImage() {
    }

    public ProductImage(UUID id, String url, String altText, int position) {
        this.id = id;
        this.url = url;
        this.altText = altText;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getAltText() {
        return altText;
    }

    public int getPosition() {
        return position;
    }

    void setProduct(Product product) {
        this.product = product;
    }
}
