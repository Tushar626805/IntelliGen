package jakarta.persistence;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface CollectionTable {String name() default ""; JoinColumn[] joinColumns() default {};}
