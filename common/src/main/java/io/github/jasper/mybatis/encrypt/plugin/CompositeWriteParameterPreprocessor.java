package io.github.jasper.mybatis.encrypt.plugin;

import org.apache.ibatis.mapping.MappedStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composite {@link WriteParameterPreprocessor}.
 *
 * <p>The composite preserves the supplied execution order and ignores empty preprocessor lists.
 * Each delegate is expected to mutate the same runtime parameter object in place.</p>
 */
public class CompositeWriteParameterPreprocessor implements WriteParameterPreprocessor {

    private final List<WriteParameterPreprocessor> preprocessors;

    /**
     * Creates a composite preprocessor.
     *
     * @param preprocessors ordered delegate preprocessors
     */
    public CompositeWriteParameterPreprocessor(List<WriteParameterPreprocessor> preprocessors) {
        if (preprocessors == null || preprocessors.isEmpty()) {
            this.preprocessors = Collections.emptyList();
            return;
        }
        this.preprocessors = Collections.unmodifiableList(new ArrayList<>(preprocessors));
    }

    @Override
    public void preprocess(MappedStatement mappedStatement, Object parameterObject) {
        for (WriteParameterPreprocessor preprocessor : preprocessors) {
            preprocessor.preprocess(mappedStatement, parameterObject);
        }
    }
}
