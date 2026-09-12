package com.testproject;

public class PmdViolation {
    public void badMethod() {
        try {
            int x = 1 / 1; // Changed divisor to avoid division by zero
        } catch (ArithmeticException e) {
            // Handle division by zero explicitly
            System.err.println("Division by zero error");
        }
    }
}