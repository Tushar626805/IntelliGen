package jakarta.persistence;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface ElementCollection {FetchType fetch() default FetchType.LAZY;}
