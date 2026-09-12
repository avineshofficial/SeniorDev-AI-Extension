package com.testproject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;

/**
 * This file uses Gson correctly. The compile detector should NOT
 * report any issues here if classpath resolution is working.
 */
public class CorrectGsonUsage {

    public static String toJson(Object obj) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        Gson gson = new Gson();
        return gson.fromJson(json, clazz);
    }

    public static void main(String[] args) {
        Map<String, Object> data = Map.of("name", "SeniorDev", "version", 2);
        String json = toJson(data);
        System.out.println("Serialized: " + json);
    }
}