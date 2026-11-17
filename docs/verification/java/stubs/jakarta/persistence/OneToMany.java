package jakarta.persistence;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface OneToMany {String mappedBy() default ""; CascadeType[] cascade() default {}; FetchType fetch() default FetchType.LAZY; boolean orphanRemoval() default false;}
