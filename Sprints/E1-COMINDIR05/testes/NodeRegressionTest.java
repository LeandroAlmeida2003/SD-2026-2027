package raft04;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.List;

/** Regressão: todo o histórico local diverge de um líder sem registos. */
public final class NodeRegressionTest {
    public static void main(String[] args) throws Exception {
        Path path = Path.of("no-p0-n1.txt");
        if (Files.exists(path)) throw new IllegalStateException("Executar numa pasta de teste vazia");
        SensorRecord record = new SensorRecord(1, 1, "S01", 20, "2026-11-10T12:00:00", 1, VectorClock.parse("1", 1));
        Files.writeString(path, record.toLine() + "\n");
        Files.writeString(Path.of("estado-p0-n1.txt"), "1;1\n");
        PartitionNode node = new PartitionNode(0, 1, 1, "127.0.0.1", "http://127.0.0.1:15999/api/v0");
        Method onBatch = PartitionNode.class.getDeclaredMethod("onBatch", String.class);
        onBatch.setAccessible(true);
        onBatch.invoke(node, new Batch(2, 2, "127.0.0.1", 7101, 1, 0, 0, List.of()).encode());
        if (!Files.readString(path).isEmpty()) throw new AssertionError("Cauda inteira não foi truncada");
        if (!Files.readString(Path.of("eventos-p0-n1.txt")).contains(";descartados=1;"))
            throw new AssertionError("Truncagem não foi registada");
        System.out.println("OK: reconciliação com prefixo e histórico remoto vazios");
    }
}
