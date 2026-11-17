package org.springframework.scheduling.annotation;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface Scheduled {long initialDelay() default 0; long fixedDelay() default 0; String cron() default "";}
