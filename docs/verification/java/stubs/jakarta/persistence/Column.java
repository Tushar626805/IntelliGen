package jakarta.persistence;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {String name() default ""; boolean nullable() default true; boolean unique() default false; int length() default 255; String columnDefinition() default ""; boolean updatable() default true; boolean insertable() default true;}
