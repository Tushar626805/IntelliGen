package org.springframework.web.bind.annotation;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface PutMapping {String[] value() default {};}
