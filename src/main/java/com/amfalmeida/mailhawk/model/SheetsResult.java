package com.amfalmeida.mailhawk.model;

public record SheetsResult(Status status, String message) {
    public enum Status {
        APPENDED, ALREADY_EXISTS, ERROR
    }
    
    public static SheetsResult appended(final String message) {
        return new SheetsResult(Status.APPENDED, message);
    }
    
    public static SheetsResult alreadyExists(final String message) {
        return new SheetsResult(Status.ALREADY_EXISTS, message);
    }
    
    public static SheetsResult error(final String message) {
        return new SheetsResult(Status.ERROR, message);
    }
}