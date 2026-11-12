package raft04;

import java.nio.file.*;

/** Reenvia um lote capturado, para testar mensagens antigas atrasadas na rede. */
public final class ReplayBatch {
    public static void main(String[] args) throws Exception {
        new IpfsPubSub(args[0]).publish(Wire.topicData(Integer.parseInt(args[1])), Files.readString(Path.of(args[2])));
    }
}
