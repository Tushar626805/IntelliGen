package com.fasterxml.jackson.annotation;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonIgnoreProperties {String[] value() default {};}
