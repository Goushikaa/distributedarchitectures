package org.dist.dbgossip;

import org.dist.util.Networks;

import java.math.BigInteger;
import java.security.MessageDigest;

import static java.math.BigInteger.TWO;

public class FBUtilities {
    /**
     * Given two bit arrays represented as BigIntegers, containing the given
     * number of significant bits, calculate a midpoint.
     *
     * @param left The left point.
     * @param right The right point.
     * @param sigbits The number of bits in the points that are significant.
     * @return A midpoint that will compare bitwise halfway between the params, and
     * a boolean representing whether a non-zero lsbit remainder was generated.
     */
    public static Pair<BigInteger,Boolean> midpoint(BigInteger left, BigInteger right, int sigbits)
    {
        BigInteger midpoint;
        boolean remainder;
        if (left.compareTo(right) < 0)
        {
            BigInteger sum = left.add(right);
            remainder = sum.testBit(0);
            midpoint = sum.shiftRight(1);
        }
        else
        {
            BigInteger max = TWO.pow(sigbits);
            // wrapping case
            BigInteger distance = max.add(right).subtract(left);
            remainder = distance.testBit(0);
            midpoint = distance.shiftRight(1).add(left).mod(max);
        }
        return Pair.create(midpoint, remainder);
    }


    public static BigInteger hash(String data)
    {
        byte[] result = hash("MD5", data.getBytes());
        BigInteger hash = new BigInteger(result);
        return hash.abs();
    }


    public static byte[] hash(String type, byte[] data)
    {
        byte[] result = null;
        try
        {
            MessageDigest messageDigest = MessageDigest.getInstance(type);
            result = messageDigest.digest(data);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static InetAddressAndPort getBroadcastAddressAndPort() {
        return new InetAddressAndPort(new Networks().ipv4Address(), 8000);
    }
}
