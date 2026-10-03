package eu.siacs.conversations.xmpp.jingle;

import com.google.common.collect.ImmutableList;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;

public class DirectConnectionUtils {

    public static List<InetAddress> getLocalAddresses() {
        final ImmutableList.Builder<InetAddress> inetAddresses = new ImmutableList.Builder<>();
        final Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (final SocketException e) {
            return inetAddresses.build();
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            final Enumeration<InetAddress> inetAddressEnumeration = networkInterface.getInetAddresses();
            while (inetAddressEnumeration.hasMoreElements()) {
                final InetAddress inetAddress = inetAddressEnumeration.nextElement();
                if (inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress()) {
                    continue;
                }
                if (inetAddress instanceof Inet6Address) {
                    //let's get rid of scope
                    try {
                        inetAddresses.add(Inet6Address.getByAddress(inetAddress.getAddress()));
                    } catch (UnknownHostException e) {
                        //ignored
                    }
                } else {
                    inetAddresses.add(inetAddress);
                }
            }
        }
        return inetAddresses.build();
    }
}
