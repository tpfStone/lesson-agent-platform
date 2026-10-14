package com.zone.lesoongen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LesoongenApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(LesoongenApplication.class, args);
        if (context.getEnvironment().matchesProfiles("structure-repair")) context.close();
    }


}
