TimeStretch2 {
	classvar synths;
	//by Sam Pluta - sampluta.com
	// Based on the Alex Ness's NessStretch algorithm in Python
	// thanks to Jean-Philippe Drecourt for his implementation of Paul Stretch, which was a huge influence on this code

	*initClass {
		synths = List.newClear(0);
		StartUp.add {

			SynthDef(\monoStretch_Overlap0, { |out = 0, bufnum, pan = 0, stretch = 100, startPos = 0, fftSize = 8192, fftMax = 65536, hiPass = 0, lowPass=0, wintype = 1, amp = 1, gate = 1, winExp = 1.2|
				var trigPeriod, sig, chain, trig, pos, jump, trigEnv, fftDelay, paulEnv, winChoice, bigEnv, warp;

				trigPeriod = (fftSize/SampleRate.ir);
				trig = Impulse.ar(1/trigPeriod);

				startPos = (startPos%1);
				pos = Line.ar(startPos*BufFrames.kr(bufnum), BufFrames.kr(bufnum), BufDur.kr(bufnum)*stretch*(1-startPos)*2);

				sig = PlayBuf.ar(1, bufnum, 1, trig, pos, 1)*SinOsc.ar(1/(2*trigPeriod)).abs;

				chain = FFT(LocalBuf(fftSize), sig, hop: 1, wintype: 0);
				chain = PV_Diffuser(chain, 1-trig);
				chain = PV_BrickWall(chain, hiPass);
				chain = PV_BrickWall(chain, lowPass);
				sig = IFFT(chain, wintype: -1);

				bigEnv = EnvGen.kr(Env.asr(0,1,0), gate, doneAction:2);
				Out.ar(sig, Pan2.ar(Mix.new(sig), pan)/2*bigEnv);
			}).writeDefFile;

		}
	}

	*stretch { |inFile, outFile, durMult, fftMax = 65536, overlaps = 2, numSplits = 9, wintype = 0, winExp=1.1, amp = 1, action|
		var sf, argses, args, nrtJam, synthChoice, synths, numChans, server, buffer0, buffer1, filtVals, fftVals, fftBufs, headerFormat;

		action ?? {action = {"done stretchin!".postln}};

		if(overlaps.size==0){
			overlaps = Array.fill(numSplits, {overlaps})
		}{
			if(overlaps.size<numSplits){(numSplits-overlaps.size).do{overlaps = overlaps.add(overlaps.last)}}
		};

		if(wintype.size==0){
			wintype = Array.fill(numSplits, {wintype})
		}{
			if(wintype.size<numSplits){(numSplits-wintype.size).do{wintype = wintype.add(wintype.last)}}
		};

		if(winExp.size==0){
			winExp = Array.fill(numSplits, {winExp})
		}{
			if(winExp.size<numSplits){(numSplits-winExp.size).do{winExp = winExp.add(winExp.last)}}
		};

		inFile = PathName(inFile);
		if((inFile.extension=="wav")||(inFile.extension=="aif")||(inFile.extension=="aiff")){

			sf = SoundFile.openRead(inFile.fullPath);

			numChans = sf.numChannels;

			if(outFile == nil){outFile = inFile.pathOnly++inFile.fileNameWithoutExtension++durMult++".wav"};

			//Server.local.options.verbosity_(verbosity);

			server = Server(("nrt"++NRT_Server_ID.next).asSymbol,
				options: Server.local.options
				.numOutputBusChannels_(numChans)
				.numInputBusChannels_(numChans)
			);

			if(sf.sampleRate<50000){
				filtVals = List.fill(8, {|i| 1/2**(i+1)}).dup.flatten.add(0).add(1).sort.clump(2);
			}{
				filtVals = List.fill(8, {|i| 1/4**(i+1)}).dup.flatten.add(0).add(1).sort.clump(2);
			};
			if(sf.sampleRate>100000){
				filtVals = List.fill(8, {|i| 1/8**(i+1)}).dup.flatten.add(0).add(1).sort.clump(2);
			};

			if((numSplits-1)<8){ filtVals = filtVals.copyRange(0, (numSplits-1))};
			filtVals.put(filtVals.size-1, [filtVals[filtVals.size-1][0], 1]);

			fftVals = List.fill(filtVals.size, {|i| fftMax/(2**i)});

			buffer0 = Buffer.new(server, 0, 1);
			buffer1 = Buffer.new(server, 0, 1);

			nrtJam = Score.new();

			nrtJam = this.addBundles(nrtJam, server, inFile, buffer0, 0, durMult, overlaps, -1, amp, filtVals, fftVals, fftMax, wintype, winExp);
			if(numChans>1){
				nrtJam = this.addBundles(nrtJam, server, inFile, buffer1, 1, durMult, overlaps, 1, amp, filtVals, fftVals, fftMax, wintype, winExp);
			};

			if((sf.duration*sf.numChannels*durMult)<(8*60*60)){headerFormat="wav"}{
				headerFormat="caf";
				outFile = PathName(outFile).pathOnly++PathName(outFile).fileNameWithoutExtension++".caf";
			};


			nrtJam.recordNRT(
				outputFilePath: outFile.standardizePath,
				sampleRate: sf.sampleRate,
				headerFormat: headerFormat,
				sampleFormat: "int24",
				options: server.options,
				duration: sf.duration*durMult*2+3,
				action: action
			);

		}{"Not an audio file!".postln;}
	}

	*addBundles {|nrtJam, server, inFile, buffer, chanNum, durMult, overlaps, pan, amp, filtVals, fftVals, fftMax, wintype, winExp|

		nrtJam.add([0.0, buffer.allocReadChannelMsg(inFile.fullPath, 0, -1, [chanNum])]);
		filtVals.do{|fv, i|
			switch(overlaps[i],
				2, {overlaps.put(i, 2)},
				{overlaps.put(i, 4)}
			);

			nrtJam.add([0.0, Synth.basicNew(("\monoStretch_Overlap0"), server).newMsg(args: [bufnum: buffer.bufnum, pan: pan, fftSize:fftVals[i].postln, fftMax:fftMax, \stretch, durMult, \hiPass, fv[0], \lowPass, fv[1]-1, \wintype, wintype[i],\amp, amp, \winExp, winExp[i].postln])])
		};
		^nrtJam
	}

}