package cr.logisticaportuaria.fleet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the local frame and the polygon geometry.
 */
class FleetGeometryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("x/y vuelven al mismo lat/lon")
    void frameRoundTrip() {
        assertThat(YardFrame.xOf(YardFrame.lonOf(123.45))).isCloseTo(123.45, within(1e-9));
        assertThat(YardFrame.yOf(YardFrame.latOf(-67.89))).isCloseTo(-67.89, within(1e-9));
        assertThat(YardFrame.xOf(YardFrame.ANCHOR_LON)).isEqualTo(0.0);
        assertThat(YardFrame.yOf(YardFrame.ANCHOR_LAT)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("un grado de longitud es mas corto que uno de latitud")
    void metersPerDegree() {
        assertThat(YardFrame.metersPerDegreeLon()).isLessThan(YardFrame.METERS_PER_DEGREE_LAT);
        assertThat(YardFrame.metersPerDegreeLon()).isGreaterThan(100_000.0);
    }

    @Test
    @DisplayName("posicion de los slots segun el layout del patio")
    void yardLayout() {
        assertThat(YardLayout.xOf("A", 1, 1)).isEqualTo(40.0);
        assertThat(YardLayout.xOf("B", 3, 2)).isEqualTo(40.0 + 60.0 + 12.0);
        assertThat(YardLayout.yOf(3)).isEqualTo(8.0);
        assertThat(YardLayout.xOf(null, 1, 1)).isEqualTo(40.0);
    }

    @Test
    @DisplayName("point-in-polygon de un rectangulo")
    void polygonContains() throws Exception {
        GeoPolygon polygon = GeoPolygon.fromGeoJson(mapper.readTree("""
                {"type":"Polygon","coordinates":[[[0,0],[120,0],[120,40],[0,40],[0,0]]]}
                """));
        assertThat(polygon.contains(60, 20)).isTrue();
        assertThat(polygon.contains(-1, 20)).isFalse();
        assertThat(polygon.contains(121, 20)).isFalse();
        assertThat(polygon.contains(60, 41)).isFalse();
        double[] centroid = polygon.centroid();
        assertThat(centroid[0]).isEqualTo(48.0);
    }

    @Test
    @DisplayName("solo se aceptan poligonos GeoJSON")
    void invalidPolygon() throws Exception {
        assertThatThrownBy(() -> GeoPolygon.fromGeoJson(mapper.readTree("""
                {"type":"Point","coordinates":[1,2]}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Polygon");
        assertThatThrownBy(() -> GeoPolygon.fromGeoJson(mapper.readTree("""
                {"type":"Polygon","coordinates":[]}
                """))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("distancia euclidiana y conversion a GeoJSON")
    void trackGeometry() {
        assertThat(TrackGeometry.distance(0, 0, 3, 4)).isEqualTo(5.0);
        var coordinates = TrackGeometry.toLonLat(java.util.List.of(0.0, 100.0),
                java.util.List.of(0.0, 50.0));
        assertThat(coordinates).hasSize(2);
        assertThat(coordinates.get(1)[0]).isCloseTo(YardFrame.lonOf(100.0), within(1e-9));
        assertThat(coordinates.get(1)[1]).isCloseTo(YardFrame.latOf(50.0), within(1e-9));
    }
}
