package art.arcane.react.api.metric;

import java.util.List;

/** Test copy of React's public source interface, same package and signatures. */
public interface ReactMetricSource {
    String sourceId();
    List<ReactMetric> metrics();
}
