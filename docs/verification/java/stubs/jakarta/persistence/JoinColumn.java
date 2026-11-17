package jakarta.persistence;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface JoinColumn {String name() default ""; boolean insertable() default true; boolean updatable() default true;}
