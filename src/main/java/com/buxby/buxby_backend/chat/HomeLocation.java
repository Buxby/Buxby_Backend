package com.buxby.buxby_backend.chat;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "buxby_chat_home_locations")
public class HomeLocation {
    @Id public String userId;
    public String label;
    public Double latitude;
    public Double longitude;
    protected HomeLocation() {}
    public HomeLocation(String userId, String label, Double latitude, Double longitude) {
        this.userId = userId; this.label = label; this.latitude = latitude; this.longitude = longitude;
    }
}
