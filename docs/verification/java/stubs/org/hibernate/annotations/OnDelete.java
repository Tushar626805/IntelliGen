package org.hibernate.annotations;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface OnDelete {OnDeleteAction action();}
