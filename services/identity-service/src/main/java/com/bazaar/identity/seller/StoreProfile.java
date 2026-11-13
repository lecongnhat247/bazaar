package com.bazaar.identity.seller;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "store_profile")
public class StoreProfile {

    @Id
    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "store_name", nullable = false, length = 120)
    private String storeName;

    @Column(length = 4000)
    private String description;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(length = 40)
    private String phone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreProfile() {
    }

    public StoreProfile(UUID sellerId, String storeName, String description, String logoUrl, String contactEmail, String phone, Instant updatedAt) {
        this.sellerId = sellerId;
        this.storeName = storeName;
        this.description = description;
        this.logoUrl = logoUrl;
        this.contactEmail = contactEmail;
        this.phone = phone;
        this.updatedAt = updatedAt;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
