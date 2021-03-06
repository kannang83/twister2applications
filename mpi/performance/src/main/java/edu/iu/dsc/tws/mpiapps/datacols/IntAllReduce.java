package edu.iu.dsc.tws.mpiapps.datacols;

import edu.iu.dsc.tws.mpiapps.Collective;
import edu.iu.dsc.tws.mpiapps.KryoSerializer;
import mpi.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Random;

public class IntAllReduce extends Collective {
  private KryoSerializer kryoSerializer;

  private Random random;

  private int rank;

  public IntAllReduce(int size, int iterations) {
    super(size, iterations);
    this.kryoSerializer = new KryoSerializer();
    this.kryoSerializer.init(new HashMap());
    random = new Random(System.nanoTime());
    try {
      rank = MPI.COMM_WORLD.getRank();
    } catch (MPIException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void execute() throws MPIException {
    IntBuffer maxSend = MPI.newIntBuffer(1);
    IntBuffer maxRecv = MPI.newIntBuffer(1);
    int rank = MPI.COMM_WORLD.getRank();


    for (int i = 0; i < iterations; i++) {
      IntBuffer sendBuffer = MPI.newIntBuffer(size + 4);
      IntBuffer receiveBuffer = MPI.newIntBuffer(size + 4);

      int[] bytes = new int[size];
//      System.out.println("Length: " + bytes.length);
      maxSend.put(0, bytes.length);
      MPI.COMM_WORLD.allReduce(maxSend, maxRecv, 1, MPI.INT, MPI.MAX);
      int length = maxRecv.get(0) + 4;
//      System.out.println("Max length: " + length);

      sendBuffer.clear();
      sendBuffer.put(bytes.length);
      sendBuffer.put(bytes);
      MPI.COMM_WORLD.allReduce(sendBuffer, receiveBuffer, length, MPI.INT, reduceOp(rank));

      if (rank == 0) {
        int receiveLength = receiveBuffer.get(0);
        int[] receiveBytes = new int[receiveLength];
        receiveBuffer.position(receiveLength + 4);
        receiveBuffer.flip();
        receiveBuffer.get();
        receiveBuffer.get(receiveBytes);
      }
      receiveBuffer.clear();
      sendBuffer.clear();
      maxRecv.clear();
      maxSend.clear();
    }
  }

  private Op reduceOp(final int rank) {
    return new Op(new UserFunction() {
      @Override
      public void call(Object o, Object o1, int i, Datatype datatype) throws MPIException {
        super.call(o, o1, i, datatype);
      }

      @Override
      public void call(ByteBuffer in, ByteBuffer inOut, int i, Datatype datatype) throws MPIException {
        int length1 = in.getInt();
        int length2 = inOut.getInt();
//        System.out.println(datatype.getName());
        byte[] firstBytes = new byte[length1 * 4];
        byte[] secondBytes = new byte[length2 * 4];

//        System.out.println(String.format("%d Partial:%d %d %d %d %d %d %d %d", rank, inOut.position(),
//            inOut.capacity(), inOut.limit(), length2, in.position(), in.capacity(), in.limit(), length1));

        in.get(firstBytes);
        inOut.get(secondBytes);

        inOut.clear();
        inOut.putInt(secondBytes.length / 4);
        inOut.put(secondBytes);
      }
    }, false);
  }
}
