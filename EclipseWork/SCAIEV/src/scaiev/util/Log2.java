package scaiev.util;

public class Log2 {
	public static int log2(int n){
	    if(n < 0) throw new IllegalArgumentException();
	    return 31 - Integer.numberOfLeadingZeros(n);
	}
	
	public static int clog2(int n) {
		 if(n <= 0) throw new IllegalArgumentException();
		 return log2(n-1)+1;
	}
}
