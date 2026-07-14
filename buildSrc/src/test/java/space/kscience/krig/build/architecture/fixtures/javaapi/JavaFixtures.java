package space.kscience.krig.build.architecture.fixtures.javaapi;

import space.kscience.krig.build.architecture.fixtures.external.ExternalType;
import space.kscience.krig.build.architecture.fixtures.external.ExternalBound;

import java.util.List;

public class JavaFixtures {
    public static class PublicNested {
        public ExternalType value;
    }

    protected static class ProtectedNested {
    }

    public interface Contract<T extends ExternalBound> {
        List<ExternalType> read();
    }

    public record SampleRecord(ExternalType value) {
    }
}
