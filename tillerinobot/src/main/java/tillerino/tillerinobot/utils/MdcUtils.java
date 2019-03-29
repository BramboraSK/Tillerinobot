package tillerino.tillerinobot.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;

public class MdcUtils {
	public interface MdcSnapshot {
		/**
		 * Applies the snapshot to the current MDC and returns a closer, which
		 * will restore the previous state.
		 */
		MdcAttributes apply();
	}

	public interface MdcAttributes extends AutoCloseable {
		void close();

		/**
		 * Sets an additional attribute and returns a new closer for both.
		 */
		default MdcAttributes and(String key, String val) {
			MdcAttributes additional = with(key, val);
			return () -> {
				additional.close();
				close();
			};
		}
	}

	public static MdcSnapshot getSnapshot() {
		Map<String, String> snapshot = MDC.getCopyOfContextMap();
		return () -> {
			Map<String, String> overwritten = new LinkedHashMap<>();
			snapshot.forEach((k, v) -> {
				String existingProperty = MDC.get(k);
				if (existingProperty != null) {
					overwritten.put(k, existingProperty);
				}
				MDC.put(k, v);
			});
			return () -> {
				snapshot.keySet().forEach(MDC::remove);
				overwritten.forEach(MDC::put);
			};
		};
	}

	public static MdcAttributes with(String key, String val) {
		String existing = MDC.get(key);
		MDC.put(key, val);
		return () -> {
			MDC.remove(key);
			if (existing != null) {
				MDC.put(key, existing);
			}
		};
	}
}
