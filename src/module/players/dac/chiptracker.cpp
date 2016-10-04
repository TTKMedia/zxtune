/**
* 
* @file
*
* @brief  ChipTracker chiptune factory
*
* @author vitamin.caig@gmail.com
*
**/

//local includes
#include "chiptracker.h"
#include "dac_properties_helper.h"
#include "dac_simple.h"
//common includes
#include <make_ptr.h>
//library includes
#include <devices/dac/sample_factories.h>
#include <formats/chiptune/digital/chiptracker.h>
#include <module/players/properties_meta.h>
#include <module/players/simple_orderlist.h>
#include <module/players/tracking.h>

namespace Module
{
namespace ChipTracker
{
  const std::size_t CHANNELS_COUNT = 4;

  const uint64_t Z80_FREQ = 3500000;
  //one cycle is 4 outputs
  const uint_t OUTS_PER_CYCLE = 4;
  const uint_t TICKS_PER_CYCLE = 890;
  const uint_t C_1_STEP = 72;
  //Z80_FREQ * (C_1_STEP / 256) / (TICKS_PER_CYCLE / OUTS_PER_CYCLE)
  const uint_t SAMPLES_FREQ = Z80_FREQ * C_1_STEP * OUTS_PER_CYCLE / TICKS_PER_CYCLE / 256;

  inline int_t StepToHz(int_t step)
  {
    //C-1 frequency is 32.7Hz
    //step * 32.7 / c-1_step
    return step * 3270 / int_t(C_1_STEP * 100);
  }
  
  enum CmdType
  {
    EMPTY,
    //offset in bytes
    SAMPLE_OFFSET,
    //step
    SLIDE
  };

  typedef DAC::SimpleModuleData ModuleData;

  class DataBuilder : public Formats::Chiptune::ChipTracker::Builder
  {
  public:
    explicit DataBuilder(DAC::PropertiesHelper& props)
      : Data(MakeRWPtr<ModuleData>())
      , Properties(props)
      , Meta(props)
      , Patterns(PatternsBuilder::Create<CHANNELS_COUNT>())
    {
      Data->Patterns = Patterns.GetResult();
      Properties.SetSamplesFrequency(SAMPLES_FREQ);
    }

    virtual Formats::Chiptune::MetaBuilder& GetMetaBuilder()
    {
      return Meta;
    }

    virtual void SetVersion(uint_t major, uint_t minor)
    {
      Properties.SetVersion(major, minor);
    }

    virtual void SetInitialTempo(uint_t tempo)
    {
      Data->InitialTempo = tempo;
    }

    virtual void SetSample(uint_t index, std::size_t loop, Binary::Data::Ptr sample)
    {
      Data->Samples.Add(index, Devices::DAC::CreateU8Sample(sample, loop));
    }

    virtual void SetPositions(const std::vector<uint_t>& positions, uint_t loop)
    {
      Data->Order = MakePtr<SimpleOrderList>(loop, positions.begin(), positions.end());
    }

    virtual Formats::Chiptune::PatternBuilder& StartPattern(uint_t index)
    {
      Patterns.SetPattern(index);
      return Patterns;
    }

    virtual void StartChannel(uint_t index)
    {
      Patterns.SetChannel(index);
    }

    virtual void SetRest()
    {
      Patterns.GetChannel().SetEnabled(false);
    }

    virtual void SetNote(uint_t note)
    {
      Patterns.GetChannel().SetEnabled(true);
      Patterns.GetChannel().SetNote(note);
    }

    virtual void SetSample(uint_t sample)
    {
      Patterns.GetChannel().SetSample(sample);
    }

    virtual void SetSlide(int_t step)
    {
      Patterns.GetChannel().AddCommand(SLIDE, step);
    }

    virtual void SetSampleOffset(uint_t offset)
    {
      Patterns.GetChannel().AddCommand(SAMPLE_OFFSET, offset);
    }

    ModuleData::Ptr GetResult() const
    {
      return Data;
    }
  private:
    const ModuleData::RWPtr Data;
    DAC::PropertiesHelper& Properties;
    MetaProperties Meta;
    PatternsBuilder Patterns;
  };

  struct GlissData
  {
    GlissData() : Sliding(), Glissade()
    {
    }
    int_t Sliding;
    int_t Glissade;

    void Reset()
    {
      Sliding = Glissade = 0;
    }

    bool Update()
    {
      Sliding += Glissade;
      return Glissade != 0;
    }
  };

  class DataRenderer : public DAC::DataRenderer
  {
  public:
    explicit DataRenderer(ModuleData::Ptr data)
      : Data(data)
    {
      Reset();
    }

    virtual void Reset()
    {
      std::fill(Gliss.begin(), Gliss.end(), GlissData());
    }

    virtual void SynthesizeData(const TrackModelState& state, DAC::TrackBuilder& track)
    {
      SynthesizeChannelsData(track);
      if (0 == state.Quirk())
      {
        GetNewLineState(state, track);
      }
    }
  private:
    void SynthesizeChannelsData(DAC::TrackBuilder& track)
    {
      for (uint_t chan = 0; chan != CHANNELS_COUNT; ++chan)
      {
        GlissData& gliss(Gliss[chan]);
        if (gliss.Update())
        {
          DAC::ChannelDataBuilder builder = track.GetChannel(chan);
          builder.SetFreqSlideHz(StepToHz(gliss.Sliding));
        }
      }
    }

    void GetNewLineState(const TrackModelState& state, DAC::TrackBuilder& track)
    {
      Gliss.assign(GlissData());
      if (const Line::Ptr line = state.LineObject())
      {
        for (uint_t chan = 0; chan != CHANNELS_COUNT; ++chan)
        {
          DAC::ChannelDataBuilder builder = track.GetChannel(chan);
          if (const Cell::Ptr src = line->GetChannel(chan))
          {
            GetNewChannelState(*src, Gliss[chan], builder);
          }
        }
      }
    };
      
    void GetNewChannelState(const Cell& src, GlissData& gliss, DAC::ChannelDataBuilder& builder)
    {
      if (const bool* enabled = src.GetEnabled())
      {
        builder.SetEnabled(*enabled);
        if (!*enabled)
        {
          builder.SetPosInSample(0);
        }
      }
      if (const uint_t* note = src.GetNote())
      {
        builder.SetNote(*note);
        builder.SetPosInSample(0);
      }
      if (const uint_t* sample = src.GetSample())
      {
        builder.SetSampleNum(*sample);
        builder.SetPosInSample(0);
      }
      builder.SetFreqSlideHz(0);
      gliss.Reset();
      for (CommandsIterator it = src.GetCommands(); it; ++it)
      {
        switch (it->Type)
        {
        case SAMPLE_OFFSET:
          builder.SetPosInSample(it->Param1);
          break;
        case SLIDE:
          gliss.Glissade = it->Param1;
          break;
        default:
          assert(!"Invalid command");
        }
      }
    }
  private:
    const ModuleData::Ptr Data;
    boost::array<GlissData, CHANNELS_COUNT> Gliss;
  };

  class Chiptune : public DAC::Chiptune
  {
  public:
    Chiptune(ModuleData::Ptr data, Parameters::Accessor::Ptr properties)
      : Data(data)
      , Properties(properties)
      , Info(CreateTrackInfo(Data, CHANNELS_COUNT))
    {
    }

    virtual Information::Ptr GetInformation() const
    {
      return Info;
    }

    virtual Parameters::Accessor::Ptr GetProperties() const
    {
      return Properties;
    }

    virtual DAC::DataIterator::Ptr CreateDataIterator() const
    {
      const TrackStateIterator::Ptr iterator = CreateTrackStateIterator(Data);
      const DAC::DataRenderer::Ptr renderer = MakePtr<DataRenderer>(Data);
      return DAC::CreateDataIterator(iterator, renderer);
    }

    virtual void GetSamples(Devices::DAC::Chip::Ptr chip) const
    {
      for (uint_t idx = 0, lim = Data->Samples.Size(); idx != lim; ++idx)
      {
        chip->SetSample(idx, Data->Samples.Get(idx));
      }
    }
  private:
    const ModuleData::Ptr Data;
    const Parameters::Accessor::Ptr Properties;
    const Information::Ptr Info;
  };

  class Factory : public DAC::Factory
  {
  public:
    virtual DAC::Chiptune::Ptr CreateChiptune(const Binary::Container& rawData, Parameters::Container::Ptr properties) const
    {
      DAC::PropertiesHelper props(*properties);
      DataBuilder dataBuilder(props);
      if (const Formats::Chiptune::Container::Ptr container = Formats::Chiptune::ChipTracker::Parse(rawData, dataBuilder))
      {
        props.SetSource(*container);
        return MakePtr<Chiptune>(dataBuilder.GetResult(), properties);
      }
      return DAC::Chiptune::Ptr();
    }
  };
  
  Factory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}
}