package com.testproject;

public class SpotBugsViolation {
    public void doSomething(String input) {
        String s = null;
        if (input.equals("test")) {
            s = "hello";
        }
        // SpotBugs should catch this NP_ALWAYS_NULL / NP_NULL_ON_SOME_PATH
        System.out.println(s.length());
    }
}
