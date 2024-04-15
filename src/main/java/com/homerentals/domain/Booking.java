package com.homerentals.domain;

import java.io.Serializable;
import java.time.LocalDate;

public class Booking implements Serializable {

    private final GuestAccount guest;
    private final Rental rental;
    private final String bookingId;

    private final LocalDate startDate;
    private final LocalDate endDate;

    public Booking(GuestAccount guest, Rental rental, String startDate, String endDate, String bookingId) {
        this.guest = guest;
        this.rental = rental;
        this.startDate = LocalDate.parse(startDate, DomainUtils.dateFormatter);
        this.endDate = LocalDate.parse(endDate, DomainUtils.dateFormatter);
        this.bookingId = bookingId;
    }

    private double calculateTotalCost() {
        return this.rental.getNightlyRate() * (this.endDate.getDayOfMonth() - this.startDate.getDayOfMonth());
    }

    public GuestAccount getGuest() {
        return this.guest;
    }

    public Rental getRental() {
        return this.rental;
    }

    public LocalDate getStartDate() {
        return this.startDate;
    }

    public LocalDate getEndDate() {
        return this.endDate;
    }

    public double getTotalCost() {
        return this.calculateTotalCost();
    }

    public String getBookingId() {
        return this.bookingId;
    }

    public boolean occursDuring(LocalDate startDate, LocalDate endDate) {
        if (this.startDate.isEqual(startDate) || this.endDate.isEqual(endDate) ||
                this.startDate.isEqual(endDate) || this.endDate.isEqual(startDate)){
            // Start date or end date matches
            // that of the time period
            return true;
        }

        if (this.startDate.isAfter(startDate) || this.endDate.isBefore(endDate)) {
            // The booking occurs entirely
            // within the time period
            return true;
        }

        if (this.startDate.isBefore(startDate) || this.endDate.isAfter(endDate)) {
            // The booking is ongoing
            // during the time period
            return true;
        }

        // If part of the booking occurs
        // within the time period, return true
        return (this.startDate.isBefore(startDate) && this.endDate.isAfter(startDate) && this.endDate.isBefore(endDate)) ||
                (this.startDate.isAfter(startDate) && this.startDate.isBefore(endDate) && this.endDate.isAfter(endDate));
    }
}
